"""
Simulation test for the /api/link/* desktop<->phone relay.

Plays both sides of the handshake against a running backend, so the relay
can be verified end-to-end without building the real Windows desktop app
or the Android app:

  fake desktop  -- register -->  backend
  fake phone    -- pair     -->  backend   (using the pair_code the "QR" carries)
  fake phone    -- command  -->  backend   (queues e.g. read_file)
  fake desktop  -- poll     -->  backend   (picks up the queued command)
  fake desktop  -- result   -->  backend   (posts a fake tool result)
  fake phone    -- status   -->  backend   (sees the result)

Also exercises the security boundaries (wrong pair code, wrong tokens,
unknown desktop) since a relay that silently accepts bad credentials is
worse than one that doesn't run at all.

Run against a live server:
    uvicorn app.main:app --reload &
    python test_link_simulation.py
    python test_link_simulation.py --backend http://13.229.100.183:8000
"""

import argparse
import sys

import requests


def check(label: str, condition: bool, detail: str = "") -> None:
    status = "PASS" if condition else "FAIL"
    print(f"[{status}] {label}" + (f" — {detail}" if detail and not condition else ""))
    if not condition:
        sys.exit(1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend", default="http://127.0.0.1:8000", help="Backend base URL")
    args = parser.parse_args()
    base = args.backend.rstrip("/") + "/api"

    print(f"Simulating desktop<->phone relay against {base}\n")

    # 1. Fake desktop registers (this is what the QR code's payload comes from)
    r = requests.post(f"{base}/link/register", json={"name": "Simulated-PC"})
    check("desktop register", r.status_code == 200, r.text)
    reg = r.json()
    desktop_id, desktop_token, pair_code = reg["desktop_id"], reg["desktop_token"], reg["pair_code"]
    print(f"   desktop_id={desktop_id}  pair_code={pair_code}")

    # 2. Fake phone "scans the QR" and pairs
    r = requests.post(
        f"{base}/link/pair",
        json={"desktop_id": desktop_id, "pair_code": pair_code, "phone_name": "Simulated-Phone"},
    )
    check("phone pair", r.status_code == 200, r.text)
    phone_token = r.json()["phone_token"]

    # 3. Fake phone queues a command — any tool name from TOOLS.md works,
    #    since the relay never inspects/executes it, just carries it.
    r = requests.post(
        f"{base}/link/command",
        json={
            "desktop_id": desktop_id,
            "phone_token": phone_token,
            "method": "read_file",
            "params": {"path": "README.md"},
        },
    )
    check("phone queue command", r.status_code == 200, r.text)
    command_id = r.json()["command_id"]

    # 4. Fake desktop polls and should see exactly that command
    r = requests.get(f"{base}/link/poll", params={"desktop_id": desktop_id, "token": desktop_token})
    check("desktop poll", r.status_code == 200, r.text)
    commands = r.json()["commands"]
    check("polled command matches", len(commands) == 1 and commands[0]["id"] == command_id)

    # 5. Fake desktop posts a result (as if tools::execute() had run it)
    r = requests.post(
        f"{base}/link/result",
        json={
            "desktop_id": desktop_id,
            "token": desktop_token,
            "command_id": command_id,
            "ok": True,
            "result": {"content": "# Argos\n..."},
        },
    )
    check("desktop post result", r.status_code == 200, r.text)

    # 6. Fake phone reads the result back
    r = requests.post(
        f"{base}/link/status",
        json={"desktop_id": desktop_id, "phone_token": phone_token, "command_id": command_id},
    )
    check("phone read status", r.status_code == 200, r.text)
    status = r.json()
    check("status is done", status["state"] == "done")
    check("result carried through", status.get("result", {}).get("content") == "# Argos\n...")

    print()

    # 7. Security boundaries — a relay that accepts bad credentials is a
    #    bigger problem than one that's simply incomplete.
    r = requests.post(
        f"{base}/link/pair",
        json={"desktop_id": desktop_id, "pair_code": "wrong-code", "phone_name": "Intruder"},
    )
    check("rejects wrong pair code", r.status_code == 401)

    r = requests.get(f"{base}/link/poll", params={"desktop_id": desktop_id, "token": "wrong-token"})
    check("rejects wrong desktop token", r.status_code == 401)

    r = requests.get(f"{base}/link/poll", params={"desktop_id": "D-DOESNOTEXIST", "token": "x"})
    check("rejects unknown desktop_id", r.status_code == 404)

    r = requests.post(
        f"{base}/link/command",
        json={"desktop_id": desktop_id, "phone_token": "pt_never_paired", "method": "read_file", "params": {}},
    )
    check("rejects unpaired phone", r.status_code == 401)

    # 8. Health check reflects the desktop we just registered
    r = requests.get(f"{base}/link/health")
    check("health endpoint", r.status_code == 200 and r.json()["desktops"] >= 1)

    print("\nAll relay checks passed.")


if __name__ == "__main__":
    main()
