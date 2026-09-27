#!/usr/bin/env python3
"""Argos terminal bridge — a zero-dependency stdio MCP server.

Lets terminal-resident AI agents (Gemini CLI, Claude Code, Codex CLI, aider,
anything that speaks MCP) join the Argos fleet:

  argos_get_task      claim the pending task Argos pasted into this terminal
  argos_send_reply    report a result back into the Argos chatbox
  argos_chat          post a free-form status line into the Argos chatbox
  argos_fleet_status  see every IDE + terminal Argos currently reaches

Wire it up in the CLI's MCP config (stdio transport):

  Gemini CLI  ~/.gemini/settings.json:
      {"mcpServers": {"argos": {"command": "python",
        "args": ["<repo>\\argos-desktop\\mcp-server\\argos_bridge.py"]}}}

  Claude Code:
      claude mcp add argos -- python <repo>\\argos-desktop\\mcp-server\\argos_bridge.py

  Codex CLI  ~/.codex/config.toml:
      [mcp_servers.argos]
      command = "python"
      args = ["<repo>\\argos-desktop\\mcp-server\\argos_bridge.py"]

The bridge reads %APPDATA%\\ArgosDesktop for the task queue and calls the
desktop's localhost command server (port 47830) for chat posts — the auth
token is DPAPI-unsealed from config.json, same as the desktop does.
"""

import ctypes
import ctypes.wintypes
import json
import os
import sys
import urllib.request
from pathlib import Path

APPDATA_DIR = Path(os.environ.get("APPDATA", "")) / "ArgosDesktop"
TASKS_FILE = APPDATA_DIR / "terminal-tasks.json"
CONFIG_FILE = APPDATA_DIR / "config.json"
COMMAND_URL = "http://localhost:47830/command"
ENTROPY = b"argos-desktop/v1/secret"


# ── DPAPI (same scheme the desktop uses: dpapi:<b64> with entropy) ──────────

class _BLOB(ctypes.Structure):
    _fields_ = [("cbData", ctypes.wintypes.DWORD),
                ("pbData", ctypes.POINTER(ctypes.c_byte))]


def _dpapi_unprotect(b64: str) -> str:
    import base64
    raw = base64.b64decode(b64)

    def blob(data: bytes) -> _BLOB:
        buf = (ctypes.c_byte * len(data)).from_buffer_copy(data)
        return _BLOB(len(data), buf)

    inb = blob(raw)
    ent = blob(ENTROPY)
    out = _BLOB()
    if not ctypes.windll.crypt32.CryptUnprotectData(
            ctypes.byref(inb), None, ctypes.byref(ent), None, None, 0,
            ctypes.byref(out)):
        raise RuntimeError("CryptUnprotectData failed")
    try:
        return ctypes.string_at(out.pbData, out.cbData).decode("utf-8")
    finally:
        ctypes.windll.kernel32.LocalFree(out.pbData)


def _token() -> str:
    cfg = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    t = (cfg.get("phone") or {}).get("token", "")
    if t.startswith("dpapi:"):
        return _dpapi_unprotect(t[6:])
    return t


def _command(method: str, params: dict) -> dict:
    body = json.dumps({"method": method, "params": params}).encode()
    req = urllib.request.Request(
        COMMAND_URL, data=body,
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + _token()})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode())


# ── Task queue file ─────────────────────────────────────────────────────────

def _read_tasks() -> dict:
    try:
        return json.loads(TASKS_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {"tasks": []}


def _write_tasks(doc: dict) -> None:
    TASKS_FILE.write_text(json.dumps(doc, indent=2), encoding="utf-8")


def _get_task(args: dict) -> str:
    pid = int(args.get("pid", 0) or 0)
    doc = _read_tasks()
    for t in doc["tasks"]:
        if t.get("status") == "pending" and (pid == 0 or t.get("pid") == pid):
            t["status"] = "claimed"
            _write_tasks(doc)
            return (f"Task {t['id']} (terminal pid {t.get('pid')}):\n"
                    f"{t.get('text')}\n\n"
                    "When finished, call argos_send_reply with this task id "
                    "and your result so it reaches the user's Argos chatbox.")
    return "No pending Argos task for this terminal."


def _send_reply(args: dict) -> str:
    text = args.get("text", "").strip()
    task_id = args.get("id", "")
    if not text:
        return "empty reply"
    if task_id:
        doc = _read_tasks()
        for t in doc["tasks"]:
            if t.get("id") == task_id:
                t["status"] = "done"
                t["result"] = text[:4000]
                _write_tasks(doc)
                break
    label = "terminal" + (f" {task_id}" if task_id else "")
    try:
        _command("chat.post", {"from": label, "text": text})
        return "Reply delivered to the Argos chatbox."
    except Exception as e:
        return f"Task marked done, but Argos desktop unreachable: {e}"


def _chat(args: dict) -> str:
    text = args.get("text", "").strip()
    if not text:
        return "empty text"
    try:
        _command("chat.post", {"from": args.get("from", "cli-agent"),
                               "text": text})
        return "Posted."
    except Exception as e:
        return f"Argos desktop unreachable: {e}"


def _fleet_status(_args: dict) -> str:
    try:
        r = _command("ide_status", {})
        out = (r.get("result") or {}).get("output")
        return out or json.dumps(r)
    except Exception as e:
        return f"Argos desktop unreachable: {e}"


TOOLS = {
    "argos_get_task": {
        "description": "Claim the pending task Argos Desktop dispatched to "
                       "this terminal window. Call this first when you see an "
                       "Argos task pasted into your input.",
        "inputSchema": {"type": "object", "properties": {
            "pid": {"type": "integer",
                    "description": "terminal process id (0 = any)"}}}},
    "argos_send_reply": {
        "description": "Report a finished task result back to the user's "
                       "Argos chatbox.",
        "inputSchema": {"type": "object", "properties": {
            "id": {"type": "string",
                   "description": "task id from argos_get_task"},
            "text": {"type": "string",
                     "description": "the result/summary to show the user"}},
            "required": ["text"]}},
    "argos_chat": {
        "description": "Post a free-form message into the Argos chatbox.",
        "inputSchema": {"type": "object", "properties": {
            "text": {"type": "string"},
            "from": {"type": "string"}},
            "required": ["text"]}},
    "argos_fleet_status": {
        "description": "List every IDE and terminal Argos Desktop currently "
                       "reaches, with workspace and provider info.",
        "inputSchema": {"type": "object", "properties": {}}},
}

HANDLERS = {
    "argos_get_task": _get_task,
    "argos_send_reply": _send_reply,
    "argos_chat": _chat,
    "argos_fleet_status": _fleet_status,
}

PROTOCOL_VERSION = "2024-11-05"


def _respond(rid, result=None, error=None):
    msg = {"jsonrpc": "2.0", "id": rid}
    if error is not None:
        msg["error"] = {"code": -32603, "message": str(error)}
    else:
        msg["result"] = result
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()


def _tool_result(text: str):
    return {"content": [{"type": "text", "text": text}]}


def main() -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception:
            continue
        method = req.get("method", "")
        rid = req.get("id")
        if method == "initialize":
            _respond(rid, {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "argos-terminal-bridge",
                               "version": "0.1.0"}})
        elif method == "tools/list":
            _respond(rid, {"tools": [
                {"name": n, "description": t["description"],
                 "inputSchema": t["inputSchema"]}
                for n, t in TOOLS.items()]})
        elif method == "tools/call":
            p = req.get("params") or {}
            fn = HANDLERS.get(p.get("name", ""))
            if not fn:
                _respond(rid, error=f"unknown tool {p.get('name')}")
                continue
            try:
                _respond(rid, _tool_result(fn(p.get("arguments") or {})))
            except Exception as e:
                _respond(rid, _tool_result(f"error: {e}"))
        elif method == "ping":
            _respond(rid, {})
        # notifications (initialized, cancelled, …) need no reply


if __name__ == "__main__":
    main()
