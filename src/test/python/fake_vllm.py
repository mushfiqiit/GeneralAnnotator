"""A stand-in for ``vllm serve`` used by the worker tests: an OpenAI-compatible HTTP server
with scripted behavior. No model, no GPU.

Environment:
  FAKE_VLLM_STATE      directory for invocation counts, the server pid, recorded requests and args
  FAKE_VLLM_MODE       ok | crash | collide-once | never-ready   (default ok)
  FAKE_VLLM_RESPONSES  JSON file: {warningId: spec}; spec keys: content, reasoning, finish, status,
                       body (raw body for errors), tokens (for /tokenize), die (exit when asked), delay
"""
import json
import os
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = os.environ["FAKE_VLLM_STATE"]
MODE = os.environ.get("FAKE_VLLM_MODE", "ok")


def append(name, value):
    with open(os.path.join(STATE, name), "a", encoding="utf-8") as f:
        f.write(json.dumps(value) + "\n")


def main():
    args = sys.argv[1:]
    port = int(args[args.index("--port") + 1])
    served = args[args.index("--served-model-name") + 1]
    os.makedirs(STATE, exist_ok=True)
    count_file = os.path.join(STATE, "invocations")
    count = int(open(count_file).read()) + 1 if os.path.exists(count_file) else 1
    with open(count_file, "w") as f:
        f.write(str(count))
    append("args.jsonl", {"args": args, "HF_HUB_OFFLINE": os.environ.get("HF_HUB_OFFLINE"),
                          "HF_HOME": os.environ.get("HF_HOME")})
    print(f"INFO fake vllm {count} starting on port {port}", flush=True)
    if MODE == "crash":
        print("RuntimeError: CUDA out of memory", flush=True)
        sys.exit(1)
    if MODE == "collide-once" and count == 1:
        print("OSError: [Errno 98] Address already in use", flush=True)
        sys.exit(1)
    if MODE == "never-ready":
        with open(os.path.join(STATE, "pid"), "w") as f:
            f.write(str(os.getpid()))
        time.sleep(600)
        return
    responses = {}
    if os.environ.get("FAKE_VLLM_RESPONSES"):
        with open(os.environ["FAKE_VLLM_RESPONSES"], encoding="utf-8") as f:
            responses = json.load(f)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def send(self, status, body):
            data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/health":
                self.send(200, b"")
            elif self.path == "/v1/models":
                self.send(200, {"object": "list", "data": [{"id": served, "object": "model"}]})
            elif self.path == "/version":
                self.send(200, {"version": "0.30.0-fake"})
            else:
                self.send(404, {"error": "not found"})

        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            prompt = request["messages"][0]["content"]
            match = re.search(r"\| warning (W[0-9]+)", prompt)
            warning = match.group(1) if match else "?"
            spec = responses.get(warning, {})
            if self.path == "/tokenize":
                self.send(200, {"count": spec.get("tokens", len(prompt) // 4), "max_model_len": 32768})
                return
            append("requests.jsonl", {"warningId": warning, "request": {k: v for k, v in request.items()
                                                                        if k != "messages"}})
            if spec.get("die"):
                os._exit(3)
            time.sleep(spec.get("delay", 0))
            if spec.get("status", 200) != 200:
                self.send(spec["status"], spec.get("body", "error").encode("utf-8"))
                return
            message = {"role": "assistant", "content": spec.get("content", "")}
            if spec.get("reasoning") is not None:
                message["reasoning_content"] = spec["reasoning"]
            self.send(200, {"id": "chatcmpl-1", "object": "chat.completion", "model": served,
                            "choices": [{"index": 0, "message": message,
                                         "finish_reason": spec.get("finish", "stop")}],
                            "usage": {"prompt_tokens": 1000, "completion_tokens": 200, "total_tokens": 1200}})

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    with open(os.path.join(STATE, "pid"), "w") as f:
        f.write(str(os.getpid()))
    print("INFO fake vllm ready", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
