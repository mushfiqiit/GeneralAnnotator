#!/usr/bin/env python3
"""GeneralAnnotator inference worker: one Slurm array task, one GPU allocation.

Runs inside a GPU job prepared by ``prepare-inference`` (the batch script loads the
modules and activates the conda environment first). It:

1. loads ``submission.json`` and ``settings.json`` from the submission directory and
   finds the prompts assigned to its array task;
2. refuses to start a model outside a Slurm job or without a large enough GPU (so
   the model is never loaded on a login node);
3. checks that the configured conda environment is active, then starts its own
   vLLM server on a free localhost port with offline, cached weights, retrying on a
   port collision, and waits for ``/health`` with a timeout;
4. sends each assigned prompt as a separate chat-completion request, one after
   another, and saves the complete raw response, any reasoning, the validated
   answer and per-prompt metadata atomically under ``results/<warningId>/``;
5. stops its own server on exit, including on SIGTERM (``scancel``, time limit).

Results that are already complete for the same prompt hash are kept and not
requested again, so a task can be resubmitted after a failure. An inference result
is a proposal: it is not checker-validated and does not establish that a warning
is resolved. Standard library only (Python 3.10+).
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

SCHEMA_VERSION = 1
SUPPORTED_TEMPLATES = ("prompt-template-1",)
COMPLETED = ("COMPLETED_PROPOSED", "COMPLETED_INSUFFICIENT_EVIDENCE")
NOTE = ("An inference result is an LLM proposal. It has not been validated by the checker and "
        "does not establish that the warning is resolved.")

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_PROMPT_FAILURES = 3
EXIT_TASK_FAILURE = 4

WARNING_ID = re.compile(r"^W[0-9]{4,}$")
ANNOTATION = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+$")
TYPE_USE_PATH = re.compile(
    r"^(field|return|parameter\[[0-9]+\])(/(typeArgument\[[0-9]+\]|arrayComponent|wildcardBound))*$")
PORT_COLLISION = re.compile(r"address already in use|errno 98|eaddrinuse", re.IGNORECASE)
POSITION_KINDS = ("FIELD", "RETURN", "PARAMETER", "TYPE_USE")
ACTIONS = ("ADD", "REMOVE")


class WorkerError(Exception):
    """A failure with a machine-readable kind."""

    def __init__(self, kind: str, message: str):
        super().__init__(message)
        self.kind = kind


class Terminated(Exception):
    """SIGTERM or SIGINT (scancel, time limit, Ctrl-C)."""


def utcnow() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def log(message: str) -> None:
    print(f"[{utcnow()}] {message}", flush=True)


# --------------------------------------------------------------------------- files

def atomic_write(path: str, data: bytes) -> None:
    """Write via a temporary file in the same directory, fsync, then rename."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = f"{path}.tmp-{os.getpid()}"
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def atomic_json(path: str, value) -> None:
    atomic_write(path, (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def inside(base: str, relative: str) -> str:
    """``base/relative`` if it stays inside ``base`` (no absolute paths, no ``..``), else raise."""
    if os.path.isabs(relative) or ".." in relative.replace("\\", "/").split("/"):
        raise WorkerError("INVALID_SUBMISSION", f"unsafe path {relative!r}")
    full = os.path.realpath(os.path.join(base, relative))
    if not full.startswith(os.path.realpath(base) + os.sep):
        raise WorkerError("INVALID_SUBMISSION", f"path {relative!r} leaves {base}")
    return full


# --------------------------------------------------------------------------- submission

def load_submission(submission_dir: str, task_index: int):
    """(submission, settings, prompt entries of the task) or raise WorkerError."""
    try:
        with open(os.path.join(submission_dir, "submission.json"), encoding="utf-8") as f:
            submission = json.load(f)
        with open(os.path.join(submission_dir, "settings.json"), encoding="utf-8") as f:
            settings = json.load(f)
    except (OSError, ValueError) as e:
        raise WorkerError("INVALID_SUBMISSION", f"cannot read submission.json/settings.json: {e}")
    if submission.get("schemaVersion") != SCHEMA_VERSION or settings.get("schemaVersion") != SCHEMA_VERSION:
        raise WorkerError("INVALID_SUBMISSION", "unsupported submission or settings schemaVersion")
    prompts = {p["warningId"]: p for p in submission.get("prompts", [])}
    tasks = {t["taskIndex"]: t["warningIds"] for t in submission.get("tasks", [])}
    if task_index not in tasks:
        raise WorkerError("INVALID_TASK", f"task {task_index} is not in submission.json "
                                          f"(tasks 0..{len(tasks) - 1})")
    entries = []
    for warning_id in tasks[task_index]:
        if warning_id not in prompts or not WARNING_ID.match(warning_id):
            raise WorkerError("INVALID_SUBMISSION", f"task {task_index} names unknown warning {warning_id!r}")
        entries.append(prompts[warning_id])
    return submission, settings, entries


# --------------------------------------------------------------------------- answers

def split_reasoning(message: dict):
    """(answer text, reasoning text or None) from a chat-completion message."""
    content = message.get("content") or ""
    reasoning = message.get("reasoning_content") or message.get("reasoning")
    if "</think>" in content:  # no reasoning parser: thinking is inline, closed by </think>
        before, content = content.rsplit("</think>", 1)
        inline = before.replace("<think>", "").strip()
        reasoning = (reasoning + "\n" + inline) if reasoning else inline
    return content.strip(), (reasoning.strip() if isinstance(reasoning, str) and reasoning.strip() else None)


def parse_answer(text: str):
    """The answer object, or raise WorkerError(MALFORMED_ANSWER). One optional ```json fence is accepted."""
    body = text.strip()
    fence = re.fullmatch(r"```(?:json)?\s*\n(.*?)\n?```", body, re.DOTALL)
    if fence:
        body = fence.group(1).strip()
    try:
        value = json.loads(body)
    except ValueError as e:
        raise WorkerError("MALFORMED_ANSWER", f"the answer is not a single JSON object: {e}")
    if not isinstance(value, dict):
        raise WorkerError("MALFORMED_ANSWER", "the answer is JSON but not an object")
    return value


def validate_answer(answer: dict, warning_id: str) -> list:
    """Errors against the prompt-template-1 response format (empty when valid)."""
    errors = []

    def string(obj, key, where, required=True):
        value = obj.get(key)
        if value is None and not required:
            return None
        if not isinstance(value, str) or not value.strip():
            errors.append(f"{where}{key} must be a non-empty string")
            return None
        return value

    if answer.get("warningId") != warning_id:
        errors.append(f"warningId is {answer.get('warningId')!r}, expected {warning_id!r}")
    status = answer.get("status")
    if status not in ("PROPOSED", "INSUFFICIENT_EVIDENCE"):
        errors.append(f"status is {status!r}, expected PROPOSED or INSUFFICIENT_EVIDENCE")
    edits = answer.get("edits")
    if not isinstance(edits, list):
        errors.append("edits must be an array")
        edits = []
    if status == "INSUFFICIENT_EVIDENCE" and edits:
        errors.append("edits must be empty when status is INSUFFICIENT_EVIDENCE")
    if status == "PROPOSED" and not edits:
        errors.append("status PROPOSED needs at least one edit")
    string(answer, "rationale", "")
    string(answer, "uncertainty", "")
    for i, edit in enumerate(edits):
        where = f"edits[{i}]."
        if not isinstance(edit, dict):
            errors.append(f"edits[{i}] must be an object")
            continue
        file = string(edit, "file", where)
        if file and (file.startswith("/") or ".." in file.split("/") or not file.endswith(".java")):
            errors.append(f"{where}file {file!r} must be a relative .java path without '..'")
        string(edit, "declaringClass", where)
        string(edit, "member", where)
        string(edit, "uncertainty", where)
        if edit.get("action") not in ACTIONS:
            errors.append(f"{where}action is {edit.get('action')!r}, expected ADD or REMOVE")
        annotation = string(edit, "annotation", where)
        if annotation and not ANNOTATION.match(annotation):
            errors.append(f"{where}annotation {annotation!r} is not a fully qualified name")
        position = edit.get("position")
        if not isinstance(position, dict) or position.get("kind") not in POSITION_KINDS:
            errors.append(f"{where}position.kind must be one of {', '.join(POSITION_KINDS)}")
            continue
        kind = position["kind"]
        index = position.get("parameterIndex")
        if kind == "PARAMETER" and not (isinstance(index, int) and not isinstance(index, bool) and index >= 0):
            errors.append(f"{where}position.parameterIndex must be an integer >= 0 for PARAMETER")
        path = position.get("typeUsePath")
        if kind == "TYPE_USE" and not (isinstance(path, str) and TYPE_USE_PATH.match(path)):
            errors.append(f"{where}position.typeUsePath {path!r} does not follow the template grammar")
    return errors


# --------------------------------------------------------------------------- HTTP

def http_request(method: str, url: str, body=None, timeout: float = 10.0):
    """(HTTP status, raw body bytes). Raises socket.timeout/TimeoutError or urllib.error.URLError."""
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json",
                                              "Authorization": "Bearer EMPTY"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


# --------------------------------------------------------------------------- node checks

def check_node(settings: dict) -> list:
    """GPUs of this allocation; refuses to run outside a Slurm job or with too little GPU memory."""
    if not os.environ.get("SLURM_JOB_ID"):
        raise WorkerError("NOT_IN_SLURM_JOB", "SLURM_JOB_ID is not set; refusing to load a model outside a "
                                              "Slurm GPU job (for example on a login node)")
    visible = os.environ.get("CUDA_VISIBLE_DEVICES", "").strip()
    if visible in ("", "-1", "NoDevFiles"):
        raise WorkerError("NO_GPU", "CUDA_VISIBLE_DEVICES is empty: this allocation has no GPU")
    smi = shutil.which("nvidia-smi")
    if smi is None:
        raise WorkerError("NO_GPU", "nvidia-smi is not available on this node")
    try:
        out = subprocess.run([smi, "--query-gpu=name,memory.total", "--format=csv,noheader,nounits"],
                             capture_output=True, text=True, timeout=120, check=True).stdout
    except (subprocess.SubprocessError, OSError) as e:
        raise WorkerError("NO_GPU", f"nvidia-smi failed: {e}")
    gpus = []
    for line in out.splitlines():
        parts = [p.strip() for p in line.rsplit(",", 1)]
        if len(parts) == 2 and parts[1].isdigit():
            gpus.append({"name": parts[0], "memoryMiB": int(parts[1])})
    if not gpus:
        raise WorkerError("NO_GPU", f"nvidia-smi lists no GPU: {out.strip()!r}")
    required = int(settings["server"]["minGpuMemoryMiB"])
    smallest = min(g["memoryMiB"] for g in gpus)
    if smallest < required:
        raise WorkerError("GPU_TOO_SMALL", f"the allocated GPU has {smallest} MiB, the configured minimum is "
                                           f"{required} MiB (GA_INFERENCE_MIN_GPU_MEMORY_MIB); request another "
                                           f"GPU type rather than risking an out-of-memory model load")
    return gpus


def check_environment(settings: dict) -> str:
    """The vllm executable of the active, configured conda environment."""
    env = settings["environment"]
    prefix = os.environ.get("CONDA_PREFIX", "")
    if not prefix or os.path.realpath(prefix) != os.path.realpath(env["condaEnv"]):
        raise WorkerError("ENVIRONMENT_ERROR", f"the active conda environment is {prefix!r}, expected "
                                               f"{env['condaEnv']!r} (the batch script activates it)")
    if not os.path.isdir(env["hfHome"]):
        raise WorkerError("ENVIRONMENT_ERROR", f"HF_HOME {env['hfHome']!r} does not exist (cached weights)")
    vllm = shutil.which("vllm")
    if vllm is None:
        raise WorkerError("ENVIRONMENT_ERROR", "vllm is not on PATH in the activated environment")
    return vllm


# --------------------------------------------------------------------------- server

def port_is_free(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False


def pick_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def tail(path: str, lines: int = 40) -> str:
    try:
        with open(path, "rb") as f:
            return b"\n".join(f.read().splitlines()[-lines:]).decode("utf-8", "replace")
    except OSError:
        return ""


def stop_process(process, grace: float) -> None:
    """SIGTERM the process group, wait up to ``grace`` seconds, then SIGKILL."""
    if process is None or process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=grace)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()


class Server:
    """A vLLM server owned by this worker."""

    def __init__(self, process, port: int, log_path: str, startup_seconds: float, attempts: int):
        self.process = process
        self.port = port
        self.log_path = log_path
        self.startup_seconds = startup_seconds
        self.attempts = attempts
        self.version = None

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def alive(self) -> bool:
        return self.process.poll() is None


def server_command(vllm: str, settings: dict, port: int) -> list:
    model = settings["model"]
    command = [vllm, "serve", model["name"], "--host", "127.0.0.1", "--port", str(port),
               "--served-model-name", model["servedName"], "--max-model-len", str(model["maxModelLen"])]
    if model.get("reasoningParser"):
        command += ["--reasoning-parser", model["reasoningParser"]]
    return command + list(model.get("extraServerArgs", []))


def server_environment(settings: dict) -> dict:
    env = dict(os.environ)
    env["HF_HOME"] = settings["environment"]["hfHome"]
    env["HF_HUB_OFFLINE"] = "1"         # use cached weights only, never download
    env["TRANSFORMERS_OFFLINE"] = "1"
    env["VLLM_NO_USAGE_STATS"] = "1"
    env["DO_NOT_TRACK"] = "1"
    return env


def start_server(vllm: str, settings: dict, log_dir: str) -> Server:
    """Start vLLM and wait until it is healthy and serves the configured model."""
    server = settings["server"]
    fixed = int(server["port"])
    attempts = int(server["portAttempts"]) if fixed == 0 else 1
    poll = float(server["healthPollSeconds"])
    last = ""
    for attempt in range(1, attempts + 1):
        port = fixed or pick_free_port()
        if not port_is_free(port):
            if fixed:
                raise WorkerError("PORT_IN_USE", f"port {port} on this node is already in use (GA_INFERENCE_PORT)")
            last = f"port {port} was taken before the server started"
            continue
        log_path = os.path.join(log_dir, f"vllm-attempt-{attempt}.log")
        os.makedirs(log_dir, exist_ok=True)
        log(f"starting vLLM on 127.0.0.1:{port} (attempt {attempt}/{attempts}, log {log_path})")
        started = time.monotonic()
        with open(log_path, "wb") as out:
            process = subprocess.Popen(server_command(vllm, settings, port), stdout=out, stderr=subprocess.STDOUT,
                                       env=server_environment(settings), start_new_session=True)
        deadline = started + float(server["startupTimeoutSeconds"])
        try:
            while True:
                if process.poll() is not None:
                    last = tail(log_path)
                    break
                try:
                    status, _ = http_request("GET", f"http://127.0.0.1:{port}/health", timeout=5)
                    if status == 200:
                        owned = Server(process, port, log_path, time.monotonic() - started, attempt)
                        verify_model(owned, settings)
                        log(f"vLLM ready after {owned.startup_seconds:.1f}s")
                        return owned
                except (urllib.error.URLError, OSError, TimeoutError):
                    pass
                if time.monotonic() >= deadline:
                    stop_process(process, float(server["stopTimeoutSeconds"]))
                    raise WorkerError("STARTUP_TIMEOUT", f"vLLM was not healthy after {server['startupTimeoutSeconds']}s; "
                                                         f"last log lines:\n{tail(log_path)}")
                time.sleep(poll)
        except BaseException:
            stop_process(process, float(server["stopTimeoutSeconds"]))
            raise
        stop_process(process, float(server["stopTimeoutSeconds"]))
        if not (PORT_COLLISION.search(last) and fixed == 0 and attempt < attempts):
            kind = "PORT_IN_USE" if PORT_COLLISION.search(last) else "STARTUP_FAILED"
            raise WorkerError(kind, f"vLLM exited during startup (code {process.returncode}); last log lines:\n{last}")
        log(f"port {port} collided; retrying on another port")
    raise WorkerError("PORT_IN_USE", f"no free port after {attempts} attempt(s): {last}")


def verify_model(server: Server, settings: dict) -> None:
    """The server on our port must be ours: it must serve the configured model name."""
    served = settings["model"]["servedName"]
    status, body = http_request("GET", f"{server.base}/v1/models", timeout=30)
    try:
        ids = [m.get("id") for m in json.loads(body).get("data", [])] if status == 200 else []
    except ValueError:
        ids = []
    if served not in ids:
        raise WorkerError("STARTUP_FAILED", f"the server on port {server.port} does not serve {served!r} "
                                            f"(GET /v1/models: HTTP {status}, models {ids})")
    try:
        status, body = http_request("GET", f"{server.base}/version", timeout=10)
        if status == 200:
            server.version = json.loads(body).get("version")
    except (urllib.error.URLError, OSError, ValueError, TimeoutError):
        pass


# --------------------------------------------------------------------------- prompts

def model_settings(settings: dict) -> dict:
    return {"model": settings["model"], "sampling": settings["sampling"],
            "requestTimeoutSeconds": settings["server"]["requestTimeoutSeconds"]}


def existing_result(result_dir: str, sha256: str):
    try:
        with open(os.path.join(result_dir, "result.json"), encoding="utf-8") as f:
            result = json.load(f)
    except (OSError, ValueError):
        return None
    if result.get("status") in COMPLETED and result.get("promptSha256") == sha256:
        return result
    return None


def next_attempt(result_dir: str) -> int:
    numbers = [int(m.group(1)) for name in (os.listdir(result_dir) if os.path.isdir(result_dir) else [])
               for m in [re.fullmatch(r"attempt-([0-9]{3,})", name)] if m]
    return max(numbers, default=0) + 1


def base_metadata(context: dict, entry: dict, attempt: int) -> dict:
    submission, settings, task_index = context["submission"], context["settings"], context["task"]
    return {
        "schemaVersion": SCHEMA_VERSION,
        "submissionId": submission["submissionId"],
        "runId": submission["runId"],
        "warningId": entry["warningId"],
        "taskIndex": task_index,
        "attempt": attempt,
        "promptFile": entry["file"],
        "promptSha256": entry["sha256"],
        "templateVersion": entry.get("templateVersion"),
        "slice": entry.get("slice"),  # copied unchanged, e.g. reproduction NOT_VALIDATED
        "settings": model_settings(settings),
        "node": context.get("node"),
        "status": None,
        "errorKind": None,
        "error": None,
        "finishReason": None,
        "usage": None,
        "timing": {},
        "validationErrors": [],
        "files": {},
        "note": NOTE,
    }


def commit(result_dir: str, attempt_dir: str, metadata: dict) -> dict:
    """Write the attempt's metadata, then result.json (the commit point)."""
    metadata["timing"].setdefault("finishedAt", utcnow())
    atomic_json(os.path.join(attempt_dir, "metadata.json"), metadata)
    atomic_json(os.path.join(result_dir, "result.json"), metadata)
    return metadata


def fail(result_dir: str, attempt_dir: str, metadata: dict, kind: str, message: str) -> dict:
    metadata["status"] = "FAILED"
    metadata["errorKind"] = kind
    metadata["error"] = message
    return commit(result_dir, attempt_dir, metadata)


def process_prompt(context: dict, server, entry: dict) -> dict:
    """Run one prompt (or keep its completed result) and return its committed metadata."""
    submission_dir, settings = context["dir"], context["settings"]
    warning_id = entry["warningId"]
    result_dir = os.path.join(submission_dir, "results", warning_id)
    kept = existing_result(result_dir, entry["sha256"])
    if kept is not None:
        log(f"{warning_id}: kept completed result ({kept['status']}, attempt {kept['attempt']})")
        return dict(kept, kept=True)
    attempt = next_attempt(result_dir)
    attempt_dir = os.path.join(result_dir, f"attempt-{attempt:03d}")
    os.makedirs(attempt_dir, exist_ok=True)
    metadata = base_metadata(context, entry, attempt)
    metadata["timing"]["startedAt"] = utcnow()

    if entry.get("templateVersion") not in SUPPORTED_TEMPLATES:
        return fail(result_dir, attempt_dir, metadata, "UNSUPPORTED_TEMPLATE",
                    f"template {entry.get('templateVersion')!r} is not one of {SUPPORTED_TEMPLATES}")
    try:
        if not str(entry.get("file", "")).startswith("prompts/"):
            raise WorkerError("INVALID_SUBMISSION", f"prompt file {entry.get('file')!r} is not under prompts/")
        with open(inside(submission_dir, entry["file"]), "rb") as f:
            prompt_bytes = f.read()
    except (OSError, WorkerError) as e:
        return fail(result_dir, attempt_dir, metadata, "PROMPT_MISSING", f"cannot read {entry['file']}: {e}")
    actual = sha256_bytes(prompt_bytes)
    metadata["actualPromptSha256"] = actual
    if actual != entry["sha256"]:
        return fail(result_dir, attempt_dir, metadata, "PROMPT_HASH_MISMATCH",
                    f"{entry['file']} has SHA-256 {actual}, submission.json records {entry['sha256']}")
    if server is None or not server.alive():
        return fail(result_dir, attempt_dir, metadata, "SERVER_UNAVAILABLE", "the vLLM server is not running")
    metadata["server"] = {"port": server.port, "vllmVersion": server.version,
                          "startupSeconds": round(server.startup_seconds, 3)}
    prompt = prompt_bytes.decode("utf-8")
    model, sampling = settings["model"], settings["sampling"]
    messages = [{"role": "user", "content": prompt}]

    # Never let the server truncate a prompt: check its length first when /tokenize is available.
    needed = int(sampling["maxTokens"]) or int(sampling["minCompletionTokens"])
    try:
        status, body = http_request("POST", f"{server.base}/tokenize",
                                    {"model": model["servedName"], "messages": messages}, timeout=120)
        count = json.loads(body).get("count") if status == 200 else None
    except (urllib.error.URLError, OSError, ValueError, TimeoutError):
        count = None
    metadata["promptTokens"] = count
    if isinstance(count, int) and count + needed > int(model["maxModelLen"]):
        return fail(result_dir, attempt_dir, metadata, "PROMPT_TOO_LONG",
                    f"the prompt has {count} tokens; with {needed} tokens for the answer it exceeds the context "
                    f"length {model['maxModelLen']}. The prompt was not sent and not truncated.")

    request = {"model": model["servedName"], "messages": messages, "stream": False,
               "temperature": sampling["temperature"], "top_p": sampling["topP"], "seed": sampling["seed"]}
    if int(sampling["topK"]) > 0:
        request["top_k"] = sampling["topK"]
    if int(sampling["maxTokens"]) > 0:
        request["max_tokens"] = sampling["maxTokens"]
    metadata["request"] = {k: v for k, v in request.items() if k != "messages"}
    started = time.monotonic()
    metadata["timing"]["requestStartedAt"] = utcnow()
    try:
        status, raw = http_request("POST", f"{server.base}/v1/chat/completions", request,
                                   timeout=float(settings["server"]["requestTimeoutSeconds"]))
    except (TimeoutError, socket.timeout):
        metadata["timing"]["requestSeconds"] = round(time.monotonic() - started, 3)
        return fail(result_dir, attempt_dir, metadata, "TIMEOUT",
                    f"no response within {settings['server']['requestTimeoutSeconds']}s")
    except (urllib.error.URLError, OSError) as e:
        metadata["timing"]["requestSeconds"] = round(time.monotonic() - started, 3)
        if isinstance(getattr(e, "reason", None), TimeoutError):
            return fail(result_dir, attempt_dir, metadata, "TIMEOUT",
                        f"no response within {settings['server']['requestTimeoutSeconds']}s")
        kind = "SERVER_UNAVAILABLE" if not server.alive() else "CONNECTION_ERROR"
        return fail(result_dir, attempt_dir, metadata, kind, f"request failed: {e}")
    metadata["timing"]["requestSeconds"] = round(time.monotonic() - started, 3)
    metadata["httpStatus"] = status

    # Save the complete raw response before interpreting it.
    try:
        response = json.loads(raw)
        raw_name = "raw-response.json"
    except ValueError:
        response = None
        raw_name = "raw-response.txt"
    atomic_write(os.path.join(attempt_dir, raw_name), raw)
    metadata["files"]["rawResponse"] = raw_name
    if status != 200:
        return fail(result_dir, attempt_dir, metadata, "HTTP_ERROR",
                    f"HTTP {status}: {raw[:500].decode('utf-8', 'replace')}")
    try:
        choice = response["choices"][0]
        message = choice.get("message") or {}
    except (TypeError, KeyError, IndexError):
        return fail(result_dir, attempt_dir, metadata, "MALFORMED_RESPONSE", "the response has no choices[0]")
    metadata["finishReason"] = choice.get("finish_reason")
    metadata["usage"] = response.get("usage")
    metadata["responseModel"] = response.get("model")
    answer_text, reasoning = split_reasoning(message)
    if reasoning:
        atomic_write(os.path.join(attempt_dir, "reasoning.txt"), reasoning.encode("utf-8"))
        metadata["files"]["reasoning"] = "reasoning.txt"
    if choice.get("finish_reason") == "length":
        return fail(result_dir, attempt_dir, metadata, "TRUNCATED",
                    "generation stopped at the token limit (finish_reason=length); the answer is incomplete")
    if not answer_text:
        return fail(result_dir, attempt_dir, metadata, "EMPTY_ANSWER",
                    f"the model returned no answer text (finish_reason={choice.get('finish_reason')!r})")
    try:
        answer = parse_answer(answer_text)
    except WorkerError as e:
        return fail(result_dir, attempt_dir, metadata, e.kind, str(e))
    errors = validate_answer(answer, warning_id)
    metadata["validationErrors"] = errors
    if errors:
        kind = "WRONG_WARNING_ID" if answer.get("warningId") != warning_id else "INVALID_ANSWER"
        return fail(result_dir, attempt_dir, metadata, kind, "; ".join(errors))
    atomic_json(os.path.join(attempt_dir, "answer.json"), answer)
    metadata["files"]["answer"] = "answer.json"
    metadata["status"] = "COMPLETED_PROPOSED" if answer["status"] == "PROPOSED" else "COMPLETED_INSUFFICIENT_EVIDENCE"
    log(f"{warning_id}: {metadata['status']} in {metadata['timing']['requestSeconds']}s")
    return commit(result_dir, attempt_dir, metadata)


def record_without_request(context: dict, entry: dict, kind: str, message: str) -> dict:
    """Record that a prompt was not sent (task-level failure), unless it already has a completed result."""
    result_dir = os.path.join(context["dir"], "results", entry["warningId"])
    kept = existing_result(result_dir, entry["sha256"])
    if kept is not None:
        return dict(kept, kept=True)
    attempt = next_attempt(result_dir)
    attempt_dir = os.path.join(result_dir, f"attempt-{attempt:03d}")
    os.makedirs(attempt_dir, exist_ok=True)
    metadata = base_metadata(context, entry, attempt)
    metadata["timing"]["startedAt"] = utcnow()
    return fail(result_dir, attempt_dir, metadata, kind, message)


# --------------------------------------------------------------------------- main

def run(submission_dir: str, task_index: int) -> int:
    submission_dir = os.path.abspath(submission_dir)
    task_started = utcnow()
    try:
        submission, settings, entries = load_submission(submission_dir, task_index)
    except WorkerError as e:
        log(f"ERROR {e.kind}: {e}")
        return EXIT_USAGE
    context = {"dir": submission_dir, "submission": submission, "settings": settings, "task": task_index,
               "node": {"host": socket.gethostname(), "slurmJobId": os.environ.get("SLURM_JOB_ID"),
                        "arrayJobId": os.environ.get("SLURM_ARRAY_JOB_ID"),
                        "arrayTaskId": os.environ.get("SLURM_ARRAY_TASK_ID")}}
    log(f"submission {submission['submissionId']} (run {submission['runId']}), task {task_index}: "
        f"{', '.join(e['warningId'] for e in entries)}")
    results = []
    server = None
    task_error = None

    def on_signal(signum, _frame):
        raise Terminated(f"received signal {signum}")

    if all(existing_result(os.path.join(submission_dir, "results", e["warningId"]), e["sha256"]) for e in entries):
        log("every assigned prompt already has a completed result; no server is started")
        for entry in entries:
            results.append(dict(existing_result(os.path.join(submission_dir, "results", entry["warningId"]),
                                                entry["sha256"]), kept=True))
        entries_to_run = []
    else:
        entries_to_run = entries
    previous = {s: signal.signal(s, on_signal) for s in (signal.SIGTERM, signal.SIGINT)}
    try:
        if entries_to_run:
            try:
                context["node"]["gpus"] = check_node(settings)
                vllm = check_environment(settings)
                # One log directory per Slurm job, so a resubmitted task never overwrites earlier logs.
                job_tag = f"job-{os.environ['SLURM_JOB_ID']}" if os.environ.get("SLURM_JOB_ID") else f"pid-{os.getpid()}"
                server = start_server(vllm, settings, os.path.join(submission_dir, "results", "logs",
                                                                    f"task-{task_index:04d}", job_tag))
            except WorkerError as e:
                task_error = e
                log(f"ERROR {e.kind}: {e}")
        for entry in entries_to_run:
            if task_error is not None:
                results.append(record_without_request(context, entry, task_error.kind, str(task_error)))
                continue
            try:
                result = process_prompt(context, server, entry)
            except Terminated as e:
                task_error = WorkerError("TERMINATED", f"{e}; the request was interrupted")
                results.append(record_without_request(context, entry, task_error.kind, str(task_error)))
                continue
            results.append(result)
            if result.get("errorKind") == "SERVER_UNAVAILABLE":
                task_error = WorkerError("SERVER_UNAVAILABLE", f"the vLLM server stopped (log {server.log_path}): "
                                                               f"{tail(server.log_path, 20)}")
    except Terminated as e:
        task_error = WorkerError("TERMINATED", str(e))
        done = {r["warningId"] for r in results}
        for entry in entries:
            if entry["warningId"] not in done:
                results.append(record_without_request(context, entry, "TERMINATED", str(e)))
    finally:
        if server is not None:
            log("stopping vLLM")
            stop_process(server.process, float(settings["server"]["stopTimeoutSeconds"]))
        for s, handler in previous.items():
            signal.signal(s, handler)

    completed = [r for r in results if r.get("status") in COMPLETED]
    summary = {
        "schemaVersion": SCHEMA_VERSION,
        "submissionId": submission["submissionId"],
        "runId": submission["runId"],
        "taskIndex": task_index,
        "node": context["node"],
        "startedAt": task_started,
        "finishedAt": utcnow(),
        "server": None if server is None else {"port": server.port, "vllmVersion": server.version,
                                               "startupSeconds": round(server.startup_seconds, 3),
                                               "portAttempts": server.attempts,
                                               "log": os.path.relpath(server.log_path, submission_dir)},
        "taskError": None if task_error is None else {"kind": task_error.kind, "message": str(task_error)},
        "prompts": [{"warningId": r["warningId"], "status": r["status"], "errorKind": r.get("errorKind"),
                     "attempt": r.get("attempt"), "kept": bool(r.get("kept"))} for r in results],
        "note": NOTE,
    }
    atomic_json(os.path.join(submission_dir, "results", "tasks", f"task-{task_index:04d}.json"), summary)
    log(f"task {task_index}: {len(completed)}/{len(entries)} completed")
    if server is None and task_error is not None and len(completed) < len(entries):
        return EXIT_TASK_FAILURE
    return EXIT_OK if len(completed) == len(entries) else EXIT_PROMPT_FAILURES


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--submission", required=True, help="the submission directory")
    parser.add_argument("--task", required=True, type=int, help="the Slurm array task index")
    args = parser.parse_args(argv)
    return run(args.submission, args.task)


if __name__ == "__main__":
    sys.exit(main())
