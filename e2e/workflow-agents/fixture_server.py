#!/usr/bin/env python3
"""Synthetic OpenAI/MCP endpoints. No external calls, credentials or customer data.

This is a deterministic protocol fixture, not an AI model. A local SQLite ledger
counts writes so the uncertain-write scenario can detect accidental replays.
"""
import argparse
import json
import os
import sqlite3
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit


def completion(body, uncertain=False):
    messages = body.get("messages", [])
    evidence = any("Check the supplied synthetic evidence" in str(m.get("content", ""))
                   for m in messages if m.get("role") == "system")
    functions = {t["function"]["name"] for t in body.get("tools", [])}
    called = {c["function"]["name"] for m in messages for c in m.get("tool_calls", [])}
    desired = [("workflow_case_data_" + ("write_case" if uncertain else "read_case"), {"caseId": "SYN-001"})]
    if not evidence:
        desired += [("search_workflow_handbook", {"query": "synthetic acceptance rule"}),
                    ("ask_workflow_evidence_agent", {"message": "Check evidence for synthetic case SYN-001."})]
    for name, args in desired:
        if name in functions and name not in called:
            message = {"role": "assistant", "content": None, "tool_calls": [{
                "id": "fixture_" + name, "type": "function",
                "function": {"name": name, "arguments": json.dumps(args)}}]}
            finish = "tool_calls"
            break
    else:
        result = ({"verified": True, "explanation": "Synthetic evidence is present."} if evidence else
                  {"decision": "accepted", "rationale": "Synthetic MCP, handbook and peer checks completed."})
        message = {"role": "assistant", "content": json.dumps(result)}
        finish = "stop"
    return {"id": "chatcmpl-synthetic", "object": "chat.completion", "created": int(time.time()),
            "model": body.get("model", "workflow-fixture"),
            "choices": [{"index": 0, "message": message, "finish_reason": finish}],
            "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20}}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass  # Never log request bodies or authorization headers.

    def send_json(self, value, status=200):
        payload = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        path = unquote(urlsplit(self.path).path)
        if path == "/repos/local/ec-ia-config/tarball/feat/workflow-agent-step":
            archive = getattr(self.server, "catalogue_archive", None)
            if archive is None:
                return self.send_json({"error": "Local catalogue archive not configured"}, 404)
            payload = Path(archive).read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/gzip")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        if self.path == "/health":
            return self.send_json({"status": "UP", "fixture": True})
        if self.path == "/fixture/stats":
            with sqlite3.connect(self.server.ledger) as db:
                writes = db.execute("SELECT COUNT(*) FROM effects").fetchone()[0]
            return self.send_json({"writes": writes})
        self.send_json({"error": "No server-initiated MCP stream"}, 405)

    def do_DELETE(self):
        self.send_json({})  # MCP session cleanup.

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        if self.path == "/fixture/mode":
            if not isinstance(body.get("uncertainWrite"), bool):
                return self.send_json({"error": "uncertainWrite must be a boolean"}, 400)
            self.server.uncertain = body["uncertainWrite"]
            return self.send_json({"uncertainWrite": self.server.uncertain})
        if self.path == "/v1/chat/completions":
            return self.send_json(completion(body, self.server.uncertain))
        if self.path == "/v1/embeddings":
            inputs = body.get("input", [])
            if not isinstance(inputs, list):
                inputs = [inputs]
            return self.send_json({"object": "list", "model": body.get("model", "fixture"),
                                   "data": [{"object": "embedding", "index": i, "embedding": [1.0, 0.0, 0.0]} for i, _ in enumerate(inputs)],
                                   "usage": {"prompt_tokens": len(inputs), "total_tokens": len(inputs)}})
        if self.path != "/mcp":
            return self.send_json({"error": "Unknown fixture endpoint"}, 404)
        method = body.get("method")
        if method and method.startswith("notifications/"):
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if method == "initialize":
            result = {"protocolVersion": body["params"]["protocolVersion"], "capabilities": {"tools": {}},
                      "serverInfo": {"name": "synthetic-case-data", "version": "1"}}
        elif method == "tools/list":
            schema = {"type": "object", "properties": {"caseId": {"type": "string"}}, "required": ["caseId"]}
            result = {"tools": [{"name": name, "description": "Synthetic case fixture only", "inputSchema": schema,
                                  "annotations": {"readOnlyHint": name == "read_case"}}
                                 for name in ("read_case", "write_case")]}
        elif method == "tools/call":
            name = body["params"]["name"]
            if name not in ("read_case", "write_case"):
                return self.send_json({"jsonrpc": "2.0", "id": body.get("id"), "error": {"code": -32602, "message": "Unknown tool"}})
            if name == "write_case":
                with sqlite3.connect(self.server.ledger) as db:
                    db.execute("INSERT INTO effects(created) VALUES(?)", (time.time(),))
                if self.server.uncertain:
                    # The effect committed; deliberately lose the response.
                    self.close_connection = True
                    return
            result = {"content": [{"type": "text", "text": json.dumps({"caseId": "SYN-001", "evidence": "present", "synthetic": True})}], "isError": False}
        else:
            return self.send_json({"jsonrpc": "2.0", "id": body.get("id"), "error": {"code": -32601, "message": "Method not found"}})
        self.send_json({"jsonrpc": "2.0", "id": body.get("id"), "result": result})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--ledger", default="/tmp/workflow-fixture.sqlite")
    parser.add_argument("--catalogue-archive", help="Synthetic local GitOps archive; never fetched externally")
    args = parser.parse_args()
    with sqlite3.connect(args.ledger) as db:
        db.execute("CREATE TABLE IF NOT EXISTS effects(id INTEGER PRIMARY KEY, created REAL NOT NULL)")
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.ledger = args.ledger
    server.catalogue_archive = args.catalogue_archive
    server.uncertain = os.getenv("FIXTURE_UNCERTAIN_WRITE", "false").lower() == "true"
    server.serve_forever()


if __name__ == "__main__":
    main()
