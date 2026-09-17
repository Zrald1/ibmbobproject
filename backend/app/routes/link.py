"""
Phone <-> desktop command link.

The Argos desktop registers here and polls for commands; the Android app
pairs by scanning the QR code the desktop shows ({url, desktop_id, pair_code})
and then queues commands for it. The backend is a pure relay — it never
executes anything itself.

Security model:
  desktop_token  — long-lived, authenticates the desktop's poll/result calls.
  pair_code      — short-lived (10 min), shown in the desktop's QR. A phone
                   that presents it is trusted to stand next to the machine.
  phone_token    — minted at pair time, scopes every phone command to one
                   desktop. Re-pairing mints a fresh one.

State is in-memory: this backend is a single-process relay. Restarting it
drops registrations — desktops simply re-register on their next poll cycle.
"""

import secrets
import threading
import time
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

router = APIRouter()

PAIR_TTL_SECONDS = 600   # pair codes expire 10 min after the QR is drawn
COMMAND_TTL_SECONDS = 300  # delivered commands/results expire after 5 min
POLL_ONLINE_SECONDS = 15  # desktop counts as "online" if it polled this recently

_lock = threading.Lock()


class _Command:
    __slots__ = ("id", "method", "params", "state", "result", "created_at", "delivered_at")

    def __init__(self, command_id: str, method: str, params: dict):
        self.id = command_id
        self.method = method
        self.params = params
        self.state = "queued"  # queued -> delivered -> done | error
        self.result: Optional[dict] = None
        self.created_at = time.time()
        self.delivered_at: Optional[float] = None


class _Desktop:
    __slots__ = ("name", "token", "pair_code", "pair_expires", "phones",
                 "queue", "commands", "last_poll_at", "registered_at")

    def __init__(self, name: str, token: str):
        self.name = name
        self.token = token
        self.pair_code = secrets.token_hex(4)  # 8 hex chars, easy to eyeball
        self.pair_expires = time.time() + PAIR_TTL_SECONDS
        self.phones: Dict[str, str] = {}       # phone_token -> phone name
        self.queue: List[str] = []             # pending command ids
        self.commands: Dict[str, _Command] = {}
        self.last_poll_at: Optional[float] = None
        self.registered_at = time.time()


_desktops: Dict[str, _Desktop] = {}  # desktop_id -> _Desktop


def _purge(d: _Desktop) -> None:
    """Drop finished/expired commands and results for one desktop."""
    now = time.time()
    dead = [cid for cid, c in d.commands.items()
            if now - c.created_at > COMMAND_TTL_SECONDS
            or (c.state in ("done", "error") and c.result is not None
                and now - (c.delivered_at or c.created_at) > COMMAND_TTL_SECONDS)]
    for cid in dead:
        d.commands.pop(cid, None)
        if cid in d.queue:
            d.queue.remove(cid)


def _get_desktop(desktop_id: str) -> _Desktop:
    d = _desktops.get(desktop_id)
    if d is None:
        raise HTTPException(status_code=404, detail="unknown desktop_id")
    return d


def _check_desktop_token(d: _Desktop, token: str) -> None:
    if not token or not secrets.compare_digest(token, d.token):
        raise HTTPException(status_code=401, detail="invalid desktop token")


def _check_phone_token(d: _Desktop, token: str) -> None:
    if not token or token not in d.phones:
        raise HTTPException(status_code=401, detail="unpaired phone")


class RegisterRequest(BaseModel):
    name: str
    desktop_id: Optional[str] = None
    desktop_token: Optional[str] = None


class PairRequest(BaseModel):
    desktop_id: str
    pair_code: str
    phone_name: str = "Argos phone"


class CommandRequest(BaseModel):
    desktop_id: str
    phone_token: str
    method: str
    params: Dict[str, Any] = {}


class ResultRequest(BaseModel):
    desktop_id: str
    token: str
    command_id: str
    ok: bool
    result: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


class StatusRequest(BaseModel):
    desktop_id: str
    phone_token: str
    command_id: str


class RevokeRequest(BaseModel):
    desktop_id: str
    token: str


@router.get("/link/health")
def link_health():
    with _lock:
        online = sum(
            1 for d in _desktops.values()
            if d.last_poll_at and time.time() - d.last_poll_at < POLL_ONLINE_SECONDS
        )
        return {"ok": True, "desktops": len(_desktops), "desktops_online": online}


@router.post("/link/register")
def link_register(req: RegisterRequest):
    """Desktop announces itself. Returning desktops pass their id+token back to
    keep their identity (and therefore their paired phones) across restarts."""
    with _lock:
        if req.desktop_id and req.desktop_token:
            d = _desktops.get(req.desktop_id)
            if d is not None and secrets.compare_digest(req.desktop_token, d.token):
                d.name = req.name
                # Rotate the pair code on every registration — an old QR
                # stops working the moment the desktop restarts.
                d.pair_code = secrets.token_hex(4)
                d.pair_expires = time.time() + PAIR_TTL_SECONDS
                _purge(d)
                return {
                    "ok": True,
                    "desktop_id": req.desktop_id,
                    "desktop_token": d.token,
                    "pair_code": d.pair_code,
                    "pair_expires": int(d.pair_expires),
                    "reused": True,
                }

        desktop_id = "D-" + secrets.token_hex(4).upper()
        token = "dt_" + secrets.token_urlsafe(24)
        _desktops[desktop_id] = _Desktop(req.name, token)
        d = _desktops[desktop_id]
        return {
            "ok": True,
            "desktop_id": desktop_id,
            "desktop_token": token,
            "pair_code": d.pair_code,
            "pair_expires": int(d.pair_expires),
            "reused": False,
        }


@router.post("/link/pair")
def link_pair(req: PairRequest):
    """Phone presents the pair_code it scanned from the desktop's QR."""
    with _lock:
        d = _get_desktop(req.desktop_id)
        if time.time() > d.pair_expires:
            raise HTTPException(status_code=410, detail="pair code expired — rescan the QR")
        if not secrets.compare_digest(req.pair_code.strip(), d.pair_code):
            raise HTTPException(status_code=401, detail="invalid pair code")
        phone_token = "pt_" + secrets.token_urlsafe(24)
        d.phones[phone_token] = req.phone_name
        return {
            "ok": True,
            "phone_token": phone_token,
            "desktop_id": req.desktop_id,
            "desktop_name": d.name,
        }


@router.post("/link/command")
def link_command(req: CommandRequest):
    """Phone queues a command for its desktop."""
    with _lock:
        d = _get_desktop(req.desktop_id)
        _check_phone_token(d, req.phone_token)
        _purge(d)
        cid = "C-" + secrets.token_hex(6).upper()
        d.commands[cid] = _Command(cid, req.method, req.params)
        d.queue.append(cid)
        online = bool(d.last_poll_at and time.time() - d.last_poll_at < POLL_ONLINE_SECONDS)
        return {"ok": True, "command_id": cid, "queued": True, "desktop_online": online}


@router.get("/link/poll")
def link_poll(desktop_id: str, token: str):
    """Desktop polls for pending commands; returned commands flip to
    'delivered' so a re-poll never hands them out twice."""
    with _lock:
        d = _get_desktop(desktop_id)
        _check_desktop_token(d, token)
        d.last_poll_at = time.time()
        _purge(d)
        out = []
        for cid in d.queue:
            c = d.commands.get(cid)
            if c is None or c.state != "queued":
                continue
            c.state = "delivered"
            c.delivered_at = time.time()
            out.append({"id": c.id, "method": c.method, "params": c.params})
        # Delivered commands leave the queue — a desktop that dies mid-run
        # can still see them via /status as 'delivered' until TTL.
        d.queue = [c for c in d.queue if d.commands.get(c) and d.commands[c].state == "queued"]
        return {"ok": True, "commands": out}


@router.post("/link/result")
def link_result(req: ResultRequest):
    """Desktop posts the outcome of a delivered command."""
    with _lock:
        d = _get_desktop(req.desktop_id)
        _check_desktop_token(d, req.token)
        c = d.commands.get(req.command_id)
        if c is None:
            raise HTTPException(status_code=404, detail="unknown command_id")
        c.state = "done" if req.ok else "error"
        c.result = req.result if req.ok else {"error": req.error or "failed"}
        if req.command_id in d.queue:
            d.queue.remove(req.command_id)
        return {"ok": True}


@router.post("/link/status")
def link_status(req: StatusRequest):
    """Phone polls for the result of a command it queued."""
    with _lock:
        d = _get_desktop(req.desktop_id)
        _check_phone_token(d, req.phone_token)
        c = d.commands.get(req.command_id)
        online = bool(d.last_poll_at and time.time() - d.last_poll_at < POLL_ONLINE_SECONDS)
        if c is None:
            return {"ok": True, "state": "expired", "desktop_online": online}
        resp: Dict[str, Any] = {
            "ok": True,
            "state": c.state,
            "desktop_online": online,
        }
        if c.state in ("done", "error"):
            resp["result"] = c.result
        return resp


@router.post("/link/revoke")
def link_revoke(req: RevokeRequest):
    """Desktop revokes all paired phones and rotates the pair code."""
    with _lock:
        d = _get_desktop(req.desktop_id)
        _check_desktop_token(d, req.token)
        d.phones.clear()
        d.pair_code = secrets.token_hex(4)
        d.pair_expires = time.time() + PAIR_TTL_SECONDS
        return {"ok": True, "pair_code": d.pair_code, "pair_expires": int(d.pair_expires)}
