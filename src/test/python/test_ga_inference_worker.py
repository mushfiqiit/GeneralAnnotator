"""Tests for the inference worker with fake vllm/nvidia-smi executables and a fake
OpenAI-compatible server. No SSH, no GPU, no model. Run with:

    python3 -m unittest discover -s src/test/python -v      (or ./gradlew workerTest)
"""
import hashlib
import importlib.util
import json
import os
import pathlib
import shutil
import signal
import stat
import sys
import tempfile
import time
import unittest
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
WORKER_FILE = HERE.parent.parent / "main" / "resources" / "generalannotator" / "inference" / "ga_inference_worker.py"
spec = importlib.util.spec_from_file_location("ga_inference_worker", WORKER_FILE)
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


def answer(warning_id, status="PROPOSED", edits=None, **extra):
    if edits is None:
        edits = [] if status == "INSUFFICIENT_EVIDENCE" else [edit()]
    value = {"warningId": warning_id, "status": status, "edits": edits,
             "rationale": "the field is read before it is assigned", "uncertainty": "low"}
    value.update(extra)
    return value


def edit(**overrides):
    value = {"file": "org/example/Foo.java", "declaringClass": "org.example.Foo", "member": "find(String)",
             "position": {"kind": "RETURN"}, "action": "ADD", "annotation": "org.jspecify.annotations.Nullable",
             "uncertainty": "callers may dereference the result"}
    value.update(overrides)
    return value


def prompt_text(warning_id):
    return (f"GeneralAnnotator prompt unit | template prompt-template-1 | run run-1 | warning {warning_id}\n\n"
            f"===== BEGIN INSTRUCTIONS =====\n...\n===== END INSTRUCTIONS =====\n")


class AnswerValidationTest(unittest.TestCase):

    def test_valid_proposals_and_insufficient_evidence(self):
        self.assertEqual([], worker.validate_answer(answer("W0001"), "W0001"))
        self.assertEqual([], worker.validate_answer(answer("W0001", "INSUFFICIENT_EVIDENCE"), "W0001"))
        positions = [{"kind": "FIELD"}, {"kind": "PARAMETER", "parameterIndex": 2},
                     {"kind": "TYPE_USE", "typeUsePath": "parameter[1]/typeArgument[0]/arrayComponent"}]
        for position in positions:
            self.assertEqual([], worker.validate_answer(answer("W0001", edits=[edit(position=position)]), "W0001"))

    def test_invalid_answers_are_reported(self):
        cases = {
            "warningId is 'W0002', expected 'W0001'": answer("W0002"),
            "edits must be empty when status is INSUFFICIENT_EVIDENCE":
                answer("W0001", "INSUFFICIENT_EVIDENCE", edits=[edit()]),
            "status PROPOSED needs at least one edit": answer("W0001", edits=[]),
            "status is 'MAYBE'": answer("W0001", "MAYBE"),
            "parameterIndex must be an integer >= 0": answer("W0001", edits=[edit(position={"kind": "PARAMETER"})]),
            "typeUsePath 'return/elements'": answer("W0001", edits=[edit(position={"kind": "TYPE_USE",
                                                                                    "typeUsePath": "return/elements"})]),
            "annotation 'Nullable' is not a fully qualified name": answer("W0001", edits=[edit(annotation="Nullable")]),
            "file '../Foo.java'": answer("W0001", edits=[edit(file="../Foo.java")]),
            "action is 'CHANGE'": answer("W0001", edits=[edit(action="CHANGE")]),
            "rationale must be a non-empty string": answer("W0001", rationale=""),
        }
        for expected, value in cases.items():
            errors = worker.validate_answer(value, "W0001")
            self.assertTrue(any(expected in e for e in errors), f"{expected!r} not in {errors}")

    def test_parse_answer_accepts_one_fence_and_rejects_prose(self):
        text = json.dumps(answer("W0001"))
        self.assertEqual("W0001", worker.parse_answer(text)["warningId"])
        self.assertEqual("W0001", worker.parse_answer(f"```json\n{text}\n```")["warningId"])
        for bad in [f"Here is my answer: {text}", "", "[1, 2]", "{\"warningId\": "]:
            with self.assertRaises(worker.WorkerError) as e:
                worker.parse_answer(bad)
            self.assertEqual("MALFORMED_ANSWER", e.exception.kind)

    def test_reasoning_is_separated(self):
        self.assertEqual(("{}", "thinking"), worker.split_reasoning({"content": "{}", "reasoning_content": "thinking"}))
        self.assertEqual(("{}", "inline thoughts"), worker.split_reasoning({"content": "<think>inline thoughts</think>\n{}"}))
        self.assertEqual(("{}", None), worker.split_reasoning({"content": "{}"}))


class WorkerRunTest(unittest.TestCase):
    """End-to-end runs of one array task against the fake server."""

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="ga worker "))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        for name, script in (("vllm", "fake_vllm.py"), ("nvidia-smi", "fake_nvidia_smi.py")):
            shim = self.bin / name
            shim.write_text(f"#!{sys.executable}\nimport runpy, sys\nsys.argv[0] = {name!r}\n"
                            f"runpy.run_path({str(HERE / script)!r}, run_name='__main__')\n")
            shim.chmod(shim.stat().st_mode | stat.S_IXUSR)
        self.state = self.tmp / "state"
        self.env_dir = self.tmp / "envs" / "llm"
        self.env_dir.mkdir(parents=True)
        (self.tmp / "hf_cache").mkdir()
        self.responses = {}
        self.submission = self.make_submission(["W0001", "W0002", "W0003"], tasks=[["W0001", "W0002", "W0003"]])
        self.env = {"PATH": f"{self.bin}{os.pathsep}{os.environ.get('PATH', '')}", "SLURM_JOB_ID": "42",
                    "SLURM_ARRAY_JOB_ID": "40", "SLURM_ARRAY_TASK_ID": "0", "CUDA_VISIBLE_DEVICES": "0",
                    "CONDA_PREFIX": str(self.env_dir), "FAKE_VLLM_STATE": str(self.state),
                    "FAKE_VLLM_RESPONSES": str(self.tmp / "responses.json")}

    def make_submission(self, ids, tasks, **server):
        directory = self.tmp / "submission"
        (directory / "prompts").mkdir(parents=True, exist_ok=True)
        prompts = []
        for i, warning_id in enumerate(ids):
            data = prompt_text(warning_id).encode("utf-8")
            (directory / "prompts" / f"{warning_id}.txt").write_bytes(data)
            task = next(t for t, members in enumerate(tasks) if warning_id in members)
            prompts.append({"warningId": warning_id, "file": f"prompts/{warning_id}.txt",
                            "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
                            "templateVersion": "prompt-template-1",
                            "slice": {"status": "GENERATED", "reproduction": "NOT_VALIDATED"}, "taskIndex": task})
        submission = {"schemaVersion": 1, "submissionId": "20261119T000000Z-inference", "runId": "run-1",
                      "prompts": prompts, "tasks": [{"taskIndex": t, "warningIds": m} for t, m in enumerate(tasks)]}
        settings = {"schemaVersion": 1,
                    "model": {"name": "Qwen/Qwen3.8-27B", "servedName": "qwen3.8-27b", "maxModelLen": 32768,
                              "reasoningParser": "qwen3", "extraServerArgs": []},
                    "sampling": {"temperature": 0.6, "topP": 0.95, "topK": 20, "seed": 0, "maxTokens": 0,
                                 "minCompletionTokens": 4096},
                    "server": dict({"port": 0, "portAttempts": 3, "startupTimeoutSeconds": 15,
                                    "healthPollSeconds": 0.1, "requestTimeoutSeconds": 10, "stopTimeoutSeconds": 5,
                                    "minGpuMemoryMiB": 70000}, **server),
                    "environment": {"modules": ["wulver", "Miniforge3"], "condaEnv": str(self.env_dir),
                                    "hfHome": str(self.tmp / "hf_cache"), "offline": True}}
        (directory / "submission.json").write_text(json.dumps(submission))
        (directory / "settings.json").write_text(json.dumps(settings))
        return directory

    def run_task(self, task=0, mode="ok", extra_env=None, drop=()):
        (self.tmp / "responses.json").write_text(json.dumps(self.responses))
        env = dict(self.env, FAKE_VLLM_MODE=mode, **(extra_env or {}))
        with mock.patch.dict(os.environ, env):
            for name in drop:
                os.environ.pop(name, None)
            return worker.run(str(self.submission), task)

    def result(self, warning_id):
        return json.loads((self.submission / "results" / warning_id / "result.json").read_text())

    def requests(self):
        path = self.state / "requests.jsonl"
        return [json.loads(l) for l in path.read_text().splitlines()] if path.exists() else []

    def assert_server_stopped(self):
        pid = int((self.state / "pid").read_text())
        for _ in range(50):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.1)
        self.fail(f"fake vLLM (pid {pid}) is still running")

    def test_completed_insufficient_and_malformed_answers(self):
        self.responses = {"W0001": {"content": json.dumps(answer("W0001")), "reasoning": "step by step"},
                          "W0002": {"content": json.dumps(answer("W0002", "INSUFFICIENT_EVIDENCE"))},
                          "W0003": {"content": "I would annotate the field."}}
        self.assertEqual(worker.EXIT_PROMPT_FAILURES, self.run_task())
        r1, r2, r3 = self.result("W0001"), self.result("W0002"), self.result("W0003")
        self.assertEqual("COMPLETED_PROPOSED", r1["status"])
        self.assertEqual("COMPLETED_INSUFFICIENT_EVIDENCE", r2["status"])
        self.assertEqual(("FAILED", "MALFORMED_ANSWER"), (r3["status"], r3["errorKind"]))
        attempt = self.submission / "results" / "W0001" / "attempt-001"
        raw = json.loads((attempt / "raw-response.json").read_text())
        self.assertEqual("step by step", raw["choices"][0]["message"]["reasoning_content"], "raw response kept whole")
        self.assertEqual("step by step", (attempt / "reasoning.txt").read_text())
        self.assertEqual(answer("W0001"), json.loads((attempt / "answer.json").read_text()))
        self.assertEqual({"prompt_tokens": 1000, "completion_tokens": 200, "total_tokens": 1200}, r1["usage"])
        self.assertEqual("stop", r1["finishReason"])
        self.assertEqual({"status": "GENERATED", "reproduction": "NOT_VALIDATED"}, r1["slice"])
        self.assertEqual(hashlib.sha256(prompt_text("W0001").encode()).hexdigest(), r1["promptSha256"])
        self.assertEqual("qwen3.8-27b", r1["settings"]["model"]["servedName"])
        self.assertEqual({"model": "qwen3.8-27b", "stream": False, "temperature": 0.6, "top_p": 0.95, "seed": 0,
                          "top_k": 20}, r1["request"])
        self.assertIn("requestSeconds", r1["timing"])
        self.assertIn("not been validated by the checker", r1["note"])
        self.assertEqual(3, len(self.requests()), "each prompt is a separate request")
        task = json.loads((self.submission / "results" / "tasks" / "task-0000.json").read_text())
        self.assertEqual(["COMPLETED_PROPOSED", "COMPLETED_INSUFFICIENT_EVIDENCE", "FAILED"],
                         [p["status"] for p in task["prompts"]])
        args = json.loads((self.state / "args.jsonl").read_text().splitlines()[0])
        self.assertEqual("1", args["HF_HUB_OFFLINE"])
        self.assertIn("--reasoning-parser", args["args"])
        self.assertEqual("127.0.0.1", args["args"][args["args"].index("--host") + 1])
        self.assert_server_stopped()

    def test_resubmission_keeps_completed_results_and_retries_failures(self):
        self.responses = {"W0001": {"content": json.dumps(answer("W0001"))},
                          "W0002": {"status": 500, "body": "internal error"},
                          "W0003": {"content": json.dumps(answer("W0003"))}}
        self.assertEqual(worker.EXIT_PROMPT_FAILURES, self.run_task())
        self.assertEqual(("FAILED", "HTTP_ERROR"), (self.result("W0002")["status"], self.result("W0002")["errorKind"]))
        self.responses["W0002"] = {"content": json.dumps(answer("W0002"))}
        (self.state / "requests.jsonl").unlink()
        self.assertEqual(worker.EXIT_OK, self.run_task())
        self.assertEqual(["W0002"], [r["warningId"] for r in self.requests()], "completed prompts are not resent")
        self.assertEqual(2, self.result("W0002")["attempt"])
        self.assertTrue((self.submission / "results" / "W0002" / "attempt-001" / "raw-response.txt").exists())
        self.assertEqual(1, self.result("W0001")["attempt"])
        # Nothing left to do: no server is started at all.
        (self.state / "invocations").unlink()
        self.assertEqual(worker.EXIT_OK, self.run_task())
        self.assertFalse((self.state / "invocations").exists())

    def test_truncated_empty_wrong_id_and_too_long_prompts_fail(self):
        self.responses = {"W0001": {"content": json.dumps(answer("W0001")), "finish": "length"},
                          "W0002": {"content": "", "reasoning": "endless thinking"},
                          "W0003": {"content": json.dumps(answer("W0009"))}}
        self.run_task()
        self.assertEqual("TRUNCATED", self.result("W0001")["errorKind"])
        self.assertEqual("EMPTY_ANSWER", self.result("W0002")["errorKind"])
        self.assertTrue((self.submission / "results" / "W0002" / "attempt-001" / "reasoning.txt").exists())
        self.assertEqual("WRONG_WARNING_ID", self.result("W0003")["errorKind"])
        self.responses = {"W0001": {"tokens": 30000}}
        shutil.rmtree(self.submission / "results")
        (self.state / "requests.jsonl").unlink()
        self.run_task()
        r = self.result("W0001")
        self.assertEqual("PROMPT_TOO_LONG", r["errorKind"])
        self.assertIn("not sent and not truncated", r["error"])
        self.assertNotIn("W0001", [q["warningId"] for q in self.requests()])

    def test_prompt_hash_mismatch_fails_only_that_prompt(self):
        (self.submission / "prompts" / "W0002.txt").write_text("tampered")
        self.responses = {w: {"content": json.dumps(answer(w))} for w in ("W0001", "W0002", "W0003")}
        self.assertEqual(worker.EXIT_PROMPT_FAILURES, self.run_task())
        self.assertEqual("PROMPT_HASH_MISMATCH", self.result("W0002")["errorKind"])
        self.assertEqual("COMPLETED_PROPOSED", self.result("W0003")["status"])

    def test_server_dying_mid_task_marks_the_rest_unavailable(self):
        self.responses = {"W0001": {"content": json.dumps(answer("W0001"))}, "W0002": {"die": True}}
        self.assertEqual(worker.EXIT_PROMPT_FAILURES, self.run_task())
        self.assertEqual("COMPLETED_PROPOSED", self.result("W0001")["status"])
        self.assertEqual("SERVER_UNAVAILABLE", self.result("W0002")["errorKind"])
        self.assertEqual("SERVER_UNAVAILABLE", self.result("W0003")["errorKind"])

    def test_startup_failure_records_every_prompt(self):
        self.assertEqual(worker.EXIT_TASK_FAILURE, self.run_task(mode="crash"))
        for w in ("W0001", "W0002", "W0003"):
            r = self.result(w)
            self.assertEqual(("FAILED", "STARTUP_FAILED"), (r["status"], r["errorKind"]))
            self.assertIn("CUDA out of memory", r["error"])
        task = json.loads((self.submission / "results" / "tasks" / "task-0000.json").read_text())
        self.assertEqual("STARTUP_FAILED", task["taskError"]["kind"])

    def test_port_collision_is_retried_on_another_port(self):
        self.responses = {w: {"content": json.dumps(answer(w))} for w in ("W0001", "W0002", "W0003")}
        self.assertEqual(worker.EXIT_OK, self.run_task(mode="collide-once"))
        self.assertEqual("2", (self.state / "invocations").read_text())
        task = json.loads((self.submission / "results" / "tasks" / "task-0000.json").read_text())
        self.assertEqual(2, task["server"]["portAttempts"])

    def test_fixed_port_in_use_is_not_shared(self):
        import socket
        with socket.socket() as busy:
            busy.bind(("127.0.0.1", 0))
            busy.listen()
            port = busy.getsockname()[1]
            self.submission = self.make_submission(["W0001"], tasks=[["W0001"]], port=port)
            self.assertEqual(worker.EXIT_TASK_FAILURE, self.run_task())
        self.assertEqual("PORT_IN_USE", self.result("W0001")["errorKind"])
        self.assertFalse((self.state / "invocations").exists(), "vLLM was not started on a busy port")

    def test_startup_timeout_kills_the_server(self):
        self.submission = self.make_submission(["W0001"], tasks=[["W0001"]], startupTimeoutSeconds=1)
        self.assertEqual(worker.EXIT_TASK_FAILURE, self.run_task(mode="never-ready"))
        self.assertEqual("STARTUP_TIMEOUT", self.result("W0001")["errorKind"])
        self.assert_server_stopped()

    def test_the_configured_1800_second_startup_deadline_is_honored(self):
        # A fake clock: each health poll advances 60 virtual seconds, so an 1800 s deadline allows 30 polls
        # (an old 1200 s deadline would have stopped after 20). Nothing really waits for half an hour.
        self.submission = self.make_submission(["W0001"], tasks=[["W0001"]], startupTimeoutSeconds=1800)
        clock = {"now": 0.0, "polls": 0}
        poll = 0.1  # healthPollSeconds of make_submission

        def monotonic():
            return clock["now"]

        def sleep(seconds):
            if seconds == poll:
                clock["polls"] += 1
                clock["now"] += 60
            else:
                clock["now"] += seconds

        with mock.patch.object(worker.time, "monotonic", monotonic), mock.patch.object(worker.time, "sleep", sleep):
            self.assertEqual(worker.EXIT_TASK_FAILURE, self.run_task(mode="never-ready"))
        r = self.result("W0001")
        self.assertEqual(("FAILED", "STARTUP_TIMEOUT"), (r["status"], r["errorKind"]))
        self.assertIn("not healthy after 1800s", r["error"])
        self.assertEqual(30, clock["polls"], "the server got the whole 1800 s, not less")

    def test_with_one_prompt_per_task_a_startup_failure_affects_only_that_prompt(self):
        ids = ["W0001", "W0002", "W0003"]
        self.submission = self.make_submission(ids, tasks=[[w] for w in ids])
        self.responses = {w: {"content": json.dumps(answer(w))} for w in ids}

        self.assertEqual(worker.EXIT_TASK_FAILURE, self.run_task(task=1, mode="crash"))
        self.assertEqual(("FAILED", "STARTUP_FAILED"), (self.result("W0002")["status"], self.result("W0002")["errorKind"]))
        for other in ("W0001", "W0003"):
            self.assertFalse((self.submission / "results" / other).exists(), f"{other} belongs to another task")
        task = json.loads((self.submission / "results" / "tasks" / "task-0001.json").read_text())
        self.assertEqual("STARTUP_FAILED", task["taskError"]["kind"])
        self.assertEqual(["W0002"], [p["warningId"] for p in task["prompts"]])

        self.assertEqual(worker.EXIT_OK, self.run_task(task=0))
        self.assertEqual(worker.EXIT_OK, self.run_task(task=2))
        self.assertEqual("COMPLETED_PROPOSED", self.result("W0001")["status"])
        self.assertEqual("COMPLETED_PROPOSED", self.result("W0003")["status"])
        self.assertEqual("STARTUP_FAILED", self.result("W0002")["errorKind"], "the failed prompt is unchanged")

    def test_no_model_outside_a_gpu_job(self):
        self.assertEqual(worker.EXIT_TASK_FAILURE, self.run_task(drop=("SLURM_JOB_ID",)))
        self.assertEqual("NOT_IN_SLURM_JOB", self.result("W0001")["errorKind"])
        self.assertIn("login node", self.result("W0001")["error"])
        self.assertFalse((self.state / "invocations").exists(), "vLLM was never started")
        shutil.rmtree(self.submission / "results")
        self.assertEqual(worker.EXIT_TASK_FAILURE, self.run_task(drop=("CUDA_VISIBLE_DEVICES",)))
        self.assertEqual("NO_GPU", self.result("W0001")["errorKind"])
        shutil.rmtree(self.submission / "results")
        self.assertEqual(worker.EXIT_TASK_FAILURE, self.run_task(extra_env={"FAKE_GPU_MEMORY_MIB": "40960"}))
        self.assertEqual("GPU_TOO_SMALL", self.result("W0001")["errorKind"])
        shutil.rmtree(self.submission / "results")
        self.assertEqual(worker.EXIT_TASK_FAILURE, self.run_task(extra_env={"CONDA_PREFIX": "/usr"}))
        self.assertEqual("ENVIRONMENT_ERROR", self.result("W0001")["errorKind"])
        self.assertFalse((self.state / "invocations").exists())

    def test_sigterm_stops_the_server_and_records_the_rest(self):
        self.responses = {"W0001": {"content": json.dumps(answer("W0001")), "delay": 5}}
        previous = signal.signal(signal.SIGALRM, lambda *_: os.kill(os.getpid(), signal.SIGTERM))
        self.addCleanup(signal.signal, signal.SIGALRM, previous)
        signal.setitimer(signal.ITIMER_REAL, 1.5)
        try:
            self.assertEqual(worker.EXIT_PROMPT_FAILURES, self.run_task())
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
        for w in ("W0001", "W0002", "W0003"):
            self.assertEqual("TERMINATED", self.result(w)["errorKind"], w)
        self.assert_server_stopped()

    def test_invalid_task_and_submission(self):
        self.assertEqual(worker.EXIT_USAGE, self.run_task(task=7))
        (self.submission / "submission.json").write_text("{")
        self.assertEqual(worker.EXIT_USAGE, self.run_task())


if __name__ == "__main__":
    unittest.main()
