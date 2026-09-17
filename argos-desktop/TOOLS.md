# Argos Tools

Everything Argos can *do* on this machine, and the three transports that reach it.

The design follows the tool models of **OpenCode** (`edit`/`write`/`bash`/`grep`/
`glob` + read-before-edit freshness) and **Kilo Code** (`read_file`/`apply_diff`/
`search_files`/`execute_command` with shell-integration output capture).

```
┌─ Cerebras agent loop (tools::schemas() → function calling)
├─ IDE bridge (extension-ide, loopback HTTP, bearer token)
├─ Phone, direct LAN     (phone_server, :47830, bearer token)
└─ Phone, backend relay  (link_client → /api/link/* poll)
         │
         ▼
   tools::execute(name, args)
         │
    ┌────┴─────┐
    ▼          ▼
 via_bridge   via_native          ← preferred when an IDE bridge is live,
 (WorkspaceEdit, in-sync with     (plain Win32/std::filesystem — works with
  open editors, undoable)          no IDE at all)
```

`tools::execute()` picks the backend automatically: the **IDE bridge** when an
editor is connected (edits land in open buffers and are undoable via
`WorkspaceEdit`), otherwise the **native** implementation. A tool that is
bridge-only returns a clear error when no IDE is connected.

All `path` args accept absolute paths anywhere on disk or paths relative to the
IDE workspace / current directory.

---

## Tool executor — `tools::execute(name, args)`

| Tool | Params | Notes |
|------|--------|-------|
| `read_file` | `path`, `offset?`, `limit?` | Line-numbered output (`1 \| …`). |
| `write_file` | `path`, `content` | Create/overwrite; parent dirs created. |
| `apply_diff` | `path`, `diff` | Surgical edits: `<<<<<<< SEARCH` / `:start_line:N` / `=======` / `>>>>>>> REPLACE` blocks, exact + whitespace-fuzzy match. Read first if unsure. |
| `insert_content` | `path`, `line`, `content` | Insert text at a 0-based line. |
| `list_files` | `path?` | List a directory (default: workspace root / cwd). |
| `search_files` | `pattern`, `path?`, `glob?`, `maxResults?` | Regex content search → file/line/snippet matches. |
| `file_stat` | `path` | exists / kind / size / mtime. |
| `rename_file` | `from`, `to`, `overwrite?` | Rename or move. |
| `copy_file` | `from`, `to`, `overwrite?` | Copy file or directory tree. |
| `make_dir` | `path` | Recursive mkdir. |
| `delete_file` | `path`, `confirmed` | Requires `confirmed: true` (or `tools.allow_destructive` in config). Bridge route deletes to the recycle bin. |
| `execute_command` | `command`, `cwd?`, `timeout_ms?` | Bridge: IDE terminal with shell-integration output capture (ANSI-stripped). Native: `cmd.exe /c`, captured stdout/stderr. |
| `open_in_editor` | `path`, `line?`, `col?` | Bridge only. |
| `get_diagnostics` | `path?` | Bridge only — compiler/linter problems. |
| `list_symbols` | `path?` | Bridge only — document outline (functions, classes). |
| `ide_status` | – | Connected IDE, workspace, active file, detected chat providers. |
| `save_file` | `path?` | Bridge only — save the open editor buffer. |
| `save_all` | – | Bridge only — save every dirty buffer. |

`tools::schemas()` (in `src/tools/tools.cpp`) returns these as
OpenAI-format function definitions — feed straight into a Cerebras
`chat.completions` `tools` array.

## IDE bridge command surface

`POST http://127.0.0.1:<port>/command` with `Authorization: Bearer <token>`
(discovery file: `%APPDATA%\ArgosDesktop\ide-bridge.json`). Full reference:
`extension-ide/README.md`. Highlights beyond the tool set:

| Method | Params | Purpose |
|--------|--------|---------|
| `ide.ping` / `ide.status` | – | liveness, workspace, tabs, providers |
| `ide.command` | `command`, `args?` | run any VS Code command |
| `ide.notify` | `message`, `level?` | toast in the IDE |
| `editor.read` / `editor.replace` | `path?`, `range?`, `text` | active-buffer read / replace |
| `editor.selection` / `editor.insert` | `text` | selection text; insert at cursor |
| `editor.close` | `path?` | close a tab |
| `workspace.folders` / `workspace.search` | `pattern?` | roots; glob search |
| `chat.providers` / `chat.focus` / `chat.new` | `provider?` | detected assistants; focus/new session |
| `chat.send` | `text`, `provider?`, `submit?` | deliver a prompt into the AI chatbox |

`chat.send` may return `delivered: "needs-paste"` — the chat input is focused
and the prompt is on the clipboard; the desktop then raises the IDE window
(`bring_process_to_front`, verified against `mainPid` from the discovery file)
and sends Ctrl+V + Enter via `SendInput`. Providers: `copilot`, `continue`,
`cline`, `roo`, `cody`, `cascade` (Devin/Windsurf), `generic`, `auto`.

## Phone command surface

Both phone transports accept `{method, params}` and return
`{ok, result|error}` — the same envelope as the bridge. Implemented in
`src/commands/dispatch.cpp`:

| Method | Params | Purpose |
|--------|--------|---------|
| `phone.ping` | – | `{pong: true, name}` |
| `desktop.status` | – | hostname, robot state, IDE bridge status, tool count |
| `tools.list` | – | the 17 tool schemas above |
| `robot.state` | `name` | scene state (idle, listening, thinking, …) |
| `robot.expression` | `name` | facial expression (happy, …) |
| `robot.gesture` | `name` | hand gesture |
| `robot.thinking` / `talking` / `listening` | `on` | state flags |
| `robot.move` | `x`, `y` | teleport the overlay |
| `robot.visible` | `on` | show/hide |
| `task.prompt` | `text` | forwarded into the IDE's AI chatbox via `chat.send` |
| *any tool name* | tool args | e.g. `read_file`, `execute_command` |
| *any bridge name* | bridge params | e.g. `file.read` — reverse-mapped to the tool |

### Transport 1 — backend relay (default)

`src/link/link_client.cpp` — the desktop registers at
`POST <backend>/api/link/register`, polls `GET /api/link/poll` every 2 s,
executes via the shared dispatcher, posts to `POST /api/link/result`. The QR
payload is `{"v":2,"app":"argos","url":…,"fallback":…,"desktop":"D-…","pair":"…"}`;
the phone pairs at `POST /api/link/pair` and reads results via
`POST /api/link/status`. Pair codes rotate on every registration and expire
after 10 min; `/api/link/revoke` unpairs every phone. Same routes exist in the
Python backend (`backend/app/routes/link.py`) and the Java fallback
(`LinkController` / `LinkService`).

### Transport 2 — direct LAN

`src/phone/phone_server.cpp` — HTTP on `0.0.0.0:47830`, bearer token,
`GET /health` unauthenticated, `POST /command` for everything else. QR payload:
`{"v":1,"app":"argos","ips":[…],"port":47830,"token":"…"}`.

## Safety model

- **Destructive ops** — `delete_file` requires `confirmed: true` unless
  `tools.allow_destructive` is set in config.json.
- **Auth** — every transport needs its credential: bridge bearer token
  (loopback only), LAN bearer token, or backend `phone_token` scoped to one
  desktop. Pair codes are one-shot-ish (10 min TTL, rotated per session).
- **Foreground gate** — Ctrl+V/Enter is only ever sent after the target IDE
  window is verified foreground by hwnd, so paste can never land in another app.
- **Secrets** — API keys, the LAN token, and `desktop_token` are DPAPI-encrypted
  in `%APPDATA%\ArgosDesktop\config.json`; nothing secret lives in the repo.

## Code map

| File | Role |
|------|------|
| `src/tools/tools.cpp` | `tools::execute` dispatch, schemas, native implementations |
| `src/bridge/ide_bridge.cpp` | WinHTTP client for the IDE bridge |
| `src/commands/dispatch.cpp` | shared `{method,params}` → action dispatcher |
| `src/phone/phone_server.cpp` | direct-LAN HTTP server (WinSock2) |
| `src/link/link_client.cpp` | backend relay client (register/poll/result) |
| `src/ui/panel_ui.cpp` | Phone tab: QR code, pairing controls, activity feed |
| `extension-ide/src/handlers.ts` | bridge command handlers |
| `extension-ide/src/chat.ts` | AI chatbox provider drivers |
| `extension-ide/src/edits.ts` | SEARCH/REPLACE diff engine |
| `backend/app/routes/link.py` | Python relay endpoints |
| `java-backend/…/LinkController.java` | Java fallback relay endpoints |
| `android/…/DesktopLink.java` | phone-side pairing + command client |
| `android/…/DesktopConnectActivity.java` | QR scanner + task UI |
