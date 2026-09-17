# Argos IDE Bridge

A VS Code–family extension that gives the **Argos desktop companion** programmatic
control of the editor and its AI chat assistants (Copilot Chat, Continue, Cline,
Roo Code, Cody, and generic chat panes).

Works in **VS Code, Cursor, Windsurf, and VSCodium** — it only uses the standard
extension API.

## How it works

- On activation the extension starts an HTTP server bound to **127.0.0.1 only**
  (default port `47820`, auto-increments if busy). It is never reachable from
  the network.
- Auth is a random bearer token. The bridge writes a discovery file to
  `%APPDATA%\ArgosDesktop\ide-bridge.json` containing `{ port, token, ide, workspace }`.
  Argos reads that file (only your Windows user account can), then calls
  `POST /command` with `Authorization: Bearer <token>`.
- `GET /health` — unauthenticated liveness probe.
- `GET /events` — Server-Sent Events stream for future push notifications.

## Command surface (`POST /command` → `{ method, params }`)

All `path` parameters accept **absolute paths anywhere on the machine** (inside
or outside the open workspace) or paths relative to the first workspace folder.

| Method              | Params                              | Purpose |
|---------------------|-------------------------------------|---------|
| `ide.ping`          | –                                   | liveness + ide name/version |
| `ide.status`        | –                                   | workspaces, active file, selection, tabs, detected chat providers |
| `ide.command`       | `{ command, args? }`                | run any VS Code command |
| `ide.notify`        | `{ message, level? }`               | toast notification |
| `editor.read`       | `{ path? }`                         | active file (or given file) contents |
| `editor.selection`  | –                                   | selected text + range |
| `editor.insert`     | `{ text }`                          | insert at cursor |
| `editor.replace`    | `{ path?, range?, text }`           | replace range / selection / file |
| `file.read`         | `{ path }`                          | read file; result includes `numbered` ("1 \| line") text |
| `file.write`        | `{ path, content, createDirs? }`    | write file |
| `file.apply_diff`   | `{ path, diff, save? }`             | apply `<<<<<<< SEARCH` / `=======` / `>>>>>>> REPLACE` blocks (Kilo Code format) via WorkspaceEdit — undoable, keeps editors in sync |
| `file.insert`       | `{ path, line, text }`              | insert text at a 0-based line |
| `file.list`         | `{ path? }`                         | list directory (default: workspace root) |
| `file.stat`         | `{ path }`                          | exists/kind/size/mtime |
| `file.rename`       | `{ from, to, overwrite? }`          | rename/move |
| `file.copy`         | `{ from, to, overwrite? }`          | copy file or directory |
| `file.mkdir`        | `{ path }`                          | create directory tree |
| `file.open`         | `{ path, line?, col? }`             | open file at position |
| `file.delete`       | `{ path, confirmed }`               | delete to trash (needs `confirmed: true`) |
| `text.search`       | `{ pattern, path?, glob?, maxResults?, contextLines? }` | regex content search (Kilo's `search_files`) |
| `editor.save`       | `{ path? }`                         | save file |
| `editor.saveAll`    | –                                   | save all dirty files |
| `editor.close`      | `{ path? }`                         | close a tab |
| `problems.get`      | `{ path? }`                         | diagnostics/errors for a file or the workspace |
| `symbols.list`      | `{ path? }`                         | document outline (functions, classes) — `list_code_definition_names` |
| `workspace.folders` | –                                   | workspace roots |
| `workspace.search`  | `{ pattern?, maxResults? }`         | glob search |
| `terminal.run`      | `{ command, name?, cwd? }`          | run shell command; captures output when shell integration is available |
| `chat.providers`    | –                                   | detected AI assistants |
| `chat.focus`        | `{ provider? }`                     | focus the chat input |
| `chat.new`          | `{ provider? }`                     | start a fresh session |
| `chat.send`         | `{ text, provider?, submit? }`      | deliver a prompt to the chatbox |

### `chat.send` result

```json
{ "ok": true, "result": { "provider": "copilot", "delivered": "command" } }
```

- `delivered: "command"` — the prompt was submitted programmatically (Copilot).
- `delivered: "needs-paste"` — the chat was focused and the prompt is on the OS
  clipboard; the caller must synthesize **Ctrl+V** then **Enter** (Argos does
  this with `SendInput`). This is the path used by webview-based assistants
  like Continue and Cline.

## Build & install

```sh
cd extension-ide
npm install
npm run compile        # or: npm run watch
```

Then either:

- **Dev:** open this folder in VS Code, press **F5** → an Extension Development
  Host window starts with the bridge running.
- **Install:** `npx @vscode/vsce package --allow-missing-repository` produces
  `argos-ide-bridge-0.1.0.vsix`; install with
  `code --install-extension argos-ide-bridge-0.1.0.vsix`
  (or `cursor`/`windsurf`/`codium` instead of `code`).

## Settings

| Setting               | Default | Notes |
|-----------------------|---------|-------|
| `argos.port`          | 47820   | Loopback port; next free port is used if busy. |
| `argos.autostart`     | true    | Start the bridge when the editor opens. |
| `argos.chatProvider`  | auto    | Target for `chat.send` (`copilot`, `continue`, `cline`, `roo`, `cody`, `generic`). |
