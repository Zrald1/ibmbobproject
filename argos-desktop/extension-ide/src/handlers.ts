// Command surface exposed to Argos over the bridge.
//
// Every method is a plain async function taking a JSON `params` object and
// returning a JSON-serialisable result. Errors propagate back as
// { ok: false, error }.
//
// All file methods accept ABSOLUTE paths — inside or outside the workspace —
// as well as paths relative to the first workspace folder.

import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { BridgeServer } from './bridge';
import * as chat from './chat';
import { applyBlocks, parseBlocks } from './edits';

const MAX_SEARCH_RESULTS = 200;
const MAX_SEARCH_FILE_BYTES = 1024 * 1024;
const SKIP_DIRS = new Set([
    '.git', 'node_modules', 'dist', 'out', 'build', '.next', '.cache',
    'bin', 'obj', 'target', '__pycache__', '.venv', 'venv',
]);

function editor(): vscode.TextEditor {
    const e = vscode.window.activeTextEditor;
    if (!e) throw new Error('no active editor');
    return e;
}

/** Resolve absolute paths verbatim; relative paths against the workspace. */
function resolvePath(p: string): string {
    if (path.isAbsolute(p)) return p;
    const root = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    return root ? path.join(root, p) : path.resolve(p);
}

function toRange(r: any): vscode.Range {
    return new vscode.Range(
        r?.startLine ?? 0,
        r?.startChar ?? 0,
        r?.endLine ?? 0,
        r?.endChar ?? 0
    );
}

function rangeJson(r: vscode.Range) {
    return {
        startLine: r.start.line,
        startChar: r.start.character,
        endLine: r.end.line,
        endChar: r.end.character,
    };
}

/** "1 | line" numbering, like read_file in Kilo Code — keeps models honest. */
function numbered(text: string): string {
    return text
        .split(/\r?\n/)
        .map((l, i) => `${i + 1} | ${l}`)
        .join('\n');
}

/** Tiny glob -> RegExp supporting *, ** and ? — enough for search filters. */
function globToRegExp(glob: string): RegExp {
    const re = glob
        .replace(/[.+^${}()|[\]\\]/g, '\\$&')
        .replace(/\*\*/g, '\u0000')
        .replace(/\*/g, '[^/\\\\]*')
        .replace(/\?/g, '[^/\\\\]')
        .replace(/\u0000/g, '.*');
    return new RegExp(`(^|[/\\\\])${re}$`, 'i');
}

function stripAnsi(s: string): string {
    return s.replace(/\[[0-9;?]*[ -/]*[@-~]/g, '');
}

async function searchDir(
    root: string,
    re: RegExp,
    glob: RegExp | null,
    maxResults: number,
    contextLines: number,
    out: any[]
): Promise<void> {
    if (out.length >= maxResults) return;
    let entries: fs.Dirent[];
    try {
        entries = fs.readdirSync(root, { withFileTypes: true });
    } catch {
        return;
    }
    for (const e of entries) {
        if (out.length >= maxResults) return;
        const full = path.join(root, e.name);
        if (e.isDirectory()) {
            if (!SKIP_DIRS.has(e.name)) await searchDir(full, re, glob, maxResults, contextLines, out);
            continue;
        }
        if (glob && !glob.test(full)) continue;
        try {
            if (fs.statSync(full).size > MAX_SEARCH_FILE_BYTES) continue;
            const buf = fs.readFileSync(full);
            if (buf.subarray(0, 512).includes(0)) continue; // binary
            const lines = buf.toString('utf8').split(/\r?\n/);
            lines.forEach((line, i) => {
                if (out.length >= maxResults || !re.test(line)) return;
                out.push({
                    file: full,
                    line: i + 1,
                    text: line.trim().slice(0, 500),
                    context:
                        contextLines > 0
                            ? lines.slice(Math.max(0, i - contextLines), i + contextLines + 1)
                            : undefined,
                });
            });
        } catch {
            /* unreadable file */
        }
    }
}

export function registerHandlers(server: BridgeServer): void {
    // ── Bridge / IDE meta ──────────────────────────────────────────────
    server.on('ide.ping', () => ({ pong: true, ide: vscode.env.appName, version: vscode.version }));

    server.on('ide.status', () => {
        const e = vscode.window.activeTextEditor;
        return {
            ide: vscode.env.appName,
            version: vscode.version,
            workspaces: (vscode.workspace.workspaceFolders || []).map((f) => f.uri.fsPath),
            activeFile: e?.document.uri.fsPath || null,
            language: e?.document.languageId || null,
            selection: e ? rangeJson(e.selection) : null,
            openTabs: vscode.window.tabGroups.all.flatMap((g) => g.tabs.map((t) => t.label)),
            chatProviders: chat.providerStatus(),
        };
    });

    server.on('ide.command', async ({ command, args }) => {
        if (!command) throw new Error('missing command');
        return { result: await vscode.commands.executeCommand(command, ...(args || [])) };
    });

    server.on('ide.notify', ({ message, level }) => {
        const text = String(message ?? '');
        if (level === 'error') void vscode.window.showErrorMessage(text);
        else if (level === 'warning') void vscode.window.showWarningMessage(text);
        else void vscode.window.showInformationMessage(text);
        return { shown: true };
    });

    // ── Editor ─────────────────────────────────────────────────────────
    server.on('editor.read', async ({ path: file }) => {
        const doc = file
            ? await vscode.workspace.openTextDocument(vscode.Uri.file(resolvePath(file)))
            : editor().document;
        const text = doc.getText();
        return {
            path: doc.uri.fsPath,
            language: doc.languageId,
            lineCount: doc.lineCount,
            dirty: doc.isDirty,
            text,
            numbered: numbered(text),
        };
    });

    server.on('editor.selection', () => {
        const e = editor();
        return {
            path: e.document.uri.fsPath,
            selection: rangeJson(e.selection),
            text: e.document.getText(e.selection),
        };
    });

    server.on('editor.insert', async ({ text }) => {
        const e = editor();
        await e.edit((b) => b.insert(e.selection.active, String(text ?? '')));
        return { inserted: true };
    });

    // Replace a range in a file (or the whole file / selection when no range).
    server.on('editor.replace', async ({ path: file, range, text, save }) => {
        const uri = file ? vscode.Uri.file(resolvePath(file)) : editor().document.uri;
        const doc = await vscode.workspace.openTextDocument(uri);
        const target = range
            ? toRange(range)
            : !file
              ? editor().selection
              : new vscode.Range(0, 0, doc.lineCount, 0);
        const edit = new vscode.WorkspaceEdit();
        edit.replace(uri, target, String(text ?? ''));
        const applied = await vscode.workspace.applyEdit(edit);
        // Save when the doc isn't on screen so disk stays in sync (same rule
        // as file.apply_diff); visible buffers stay dirty for user review.
        const visible = vscode.window.visibleTextEditors.some(
            (e) => e.document.uri.fsPath === doc.uri.fsPath
        );
        let saved = false;
        if (applied && (save === true || (!visible && save !== false))) {
            saved = await doc.save();
        }
        return { applied, saved };
    });

    server.on('editor.save', async ({ path: file }) => {
        if (file) {
            const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(resolvePath(file)));
            return { saved: await doc.save(), path: doc.uri.fsPath };
        }
        const e = editor();
        return { saved: await e.document.save(), path: e.document.uri.fsPath };
    });

    server.on('editor.saveAll', async () => ({ saved: await vscode.workspace.saveAll(false) }));

    server.on('editor.close', async ({ path: file }) => {
        const uri = file ? vscode.Uri.file(resolvePath(file)) : editor().document.uri;
        const target = vscode.window.tabGroups.all
            .flatMap((g) => g.tabs)
            .find((t) => (t.input as any)?.uri?.fsPath === uri.fsPath);
        if (!target) return { closed: false };
        return { closed: await vscode.window.tabGroups.close(target) };
    });

    // ── Filesystem (absolute OR workspace-relative paths) ─────────────
    server.on('file.read', async ({ path: file }) => {
        if (!file) throw new Error('missing path');
        const full = resolvePath(file);
        const text = fs.readFileSync(full, 'utf8');
        return {
            path: full,
            lineCount: text.split(/\r?\n/).length,
            text,
            numbered: numbered(text),
        };
    });

    server.on('file.write', async ({ path: file, content, createDirs = true }) => {
        if (!file) throw new Error('missing path');
        const full = resolvePath(file);
        if (createDirs) fs.mkdirSync(path.dirname(full), { recursive: true });
        fs.writeFileSync(full, String(content ?? ''), 'utf8');
        return { written: true, path: full, bytes: Buffer.byteLength(String(content ?? '')) };
    });

    // Surgical edits: Kilo Code-style <<<<<<< SEARCH / ======= / >>>>>>> REPLACE
    // blocks, applied through WorkspaceEdit so open editors stay in sync and
    // Ctrl+Z still works.
    server.on('file.apply_diff', async ({ path: file, diff, save }) => {
        if (!file || !diff) throw new Error('missing path or diff');
        const uri = vscode.Uri.file(resolvePath(file));
        const doc = await vscode.workspace.openTextDocument(uri);
        const visible = vscode.window.visibleTextEditors.some(
            (e) => e.document.uri.fsPath === doc.uri.fsPath
        );
        const blocks = parseBlocks(String(diff));
        if (blocks.length === 0) throw new Error('no SEARCH/REPLACE blocks in diff');
        const result = applyBlocks(doc.getText(), blocks);
        let applied = false;
        let saved = false;
        if (result.applied > 0) {
            const edit = new vscode.WorkspaceEdit();
            const full = new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length));
            edit.replace(uri, full, result.content);
            applied = await vscode.workspace.applyEdit(edit);
            if (applied && (save === true || (!visible && save !== false))) {
                saved = await doc.save();
            }
        }
        return { applied, blocks: result.applied, failures: result.failures, saved };
    });

    // Insert text at a 0-based line (Kilo's insert_content).
    server.on('file.insert', async ({ path: file, line, text, save }) => {
        if (!file) throw new Error('missing path');
        const uri = vscode.Uri.file(resolvePath(file));
        const doc = await vscode.workspace.openTextDocument(uri);
        const at = Math.max(0, Math.min(Number(line) || 0, doc.lineCount));
        const edit = new vscode.WorkspaceEdit();
        edit.insert(uri, new vscode.Position(at, 0), String(text ?? ''));
        const applied = await vscode.workspace.applyEdit(edit);
        const visible = vscode.window.visibleTextEditors.some(
            (e) => e.document.uri.fsPath === doc.uri.fsPath
        );
        let saved = false;
        if (applied && (save === true || (!visible && save !== false))) {
            saved = await doc.save();
        }
        return { applied, saved };
    });

    server.on('file.list', async ({ path: dir }) => {
        const root = dir ? resolvePath(dir) : vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
        if (!root) throw new Error('no directory and no open workspace');
        const entries = fs.readdirSync(root, { withFileTypes: true }).map((d) => ({
            name: d.name,
            kind: d.isDirectory() ? 'dir' : 'file',
        }));
        return { path: root, entries };
    });

    server.on('file.stat', async ({ path: file }) => {
        if (!file) throw new Error('missing path');
        const full = resolvePath(file);
        if (!fs.existsSync(full)) return { exists: false, path: full };
        const s = fs.statSync(full);
        return {
            exists: true,
            path: full,
            kind: s.isDirectory() ? 'dir' : 'file',
            size: s.size,
            modified: s.mtime.toISOString(),
        };
    });

    server.on('file.rename', async ({ from, to, overwrite = false }) => {
        if (!from || !to) throw new Error('missing from/to');
        const src = resolvePath(from);
        const dst = resolvePath(to);
        if (fs.existsSync(dst) && !overwrite) return { renamed: false, reason: 'target exists' };
        fs.mkdirSync(path.dirname(dst), { recursive: true });
        fs.renameSync(src, dst);
        return { renamed: true, from: src, to: dst };
    });

    server.on('file.copy', async ({ from, to, overwrite = true }) => {
        if (!from || !to) throw new Error('missing from/to');
        const src = resolvePath(from);
        const dst = resolvePath(to);
        fs.mkdirSync(path.dirname(dst), { recursive: true });
        fs.cpSync(src, dst, { recursive: true, force: overwrite });
        return { copied: true, from: src, to: dst };
    });

    server.on('file.mkdir', async ({ path: dir }) => {
        if (!dir) throw new Error('missing path');
        const full = resolvePath(dir);
        fs.mkdirSync(full, { recursive: true });
        return { created: true, path: full };
    });

    server.on('file.open', async ({ path: file, line = 0, col = 0 }) => {
        if (!file) throw new Error('missing path');
        const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(resolvePath(file)));
        const e = await vscode.window.showTextDocument(doc);
        const pos = new vscode.Position(line, col);
        e.selection = new vscode.Selection(pos, pos);
        e.revealRange(new vscode.Range(pos, pos));
        return { opened: doc.uri.fsPath };
    });

    server.on('file.delete', async ({ path: file, confirmed }) => {
        if (!file) throw new Error('missing path');
        if (!confirmed) return { deleted: false, needsConfirmation: true };
        await vscode.workspace.fs.delete(vscode.Uri.file(resolvePath(file)), {
            useTrash: true,
            recursive: true,
        });
        return { deleted: true };
    });

    // Regex content search over any directory — Kilo's search_files.
    server.on('text.search', async ({ pattern, path: dir, glob, maxResults = MAX_SEARCH_RESULTS, contextLines = 0 }) => {
        if (!pattern) throw new Error('missing pattern');
        const root = dir ? resolvePath(dir) : vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
        if (!root) throw new Error('no directory and no open workspace');
        const re = new RegExp(pattern, 'g');
        const globRe = glob ? globToRegExp(glob) : null;
        const results: any[] = [];
        await searchDir(root, re, globRe, Math.min(maxResults, MAX_SEARCH_RESULTS), contextLines, results);
        return { root, count: results.length, truncated: results.length >= maxResults, matches: results };
    });

    // ── Code intelligence ──────────────────────────────────────────────
    server.on('problems.get', async ({ path: file }) => {
        const items: any[] = [];
        const push = (uri: vscode.Uri, diags: readonly vscode.Diagnostic[]) => {
            for (const d of diags) {
                items.push({
                    file: uri.fsPath,
                    severity: vscode.DiagnosticSeverity[d.severity],
                    message: d.message,
                    range: rangeJson(d.range),
                    source: d.source,
                });
            }
        };
        if (file) {
            const uri = vscode.Uri.file(resolvePath(file));
            push(uri, vscode.languages.getDiagnostics(uri));
        } else {
            for (const [uri, diags] of vscode.languages.getDiagnostics()) push(uri, diags);
        }
        return { count: items.length, diagnostics: items.slice(0, 200) };
    });

    server.on('symbols.list', async ({ path: file }) => {
        const uri = file ? vscode.Uri.file(resolvePath(file)) : editor().document.uri;
        const symbols = await vscode.commands.executeCommand<any[]>(
            'vscode.executeDocumentSymbolProvider',
            uri
        );
        const flat: any[] = [];
        const walk = (list: any[], depth: number) => {
            for (const s of list || []) {
                flat.push({
                    name: s.name,
                    kind: vscode.SymbolKind[s.kind],
                    line: (s.selectionRange ?? s.range).start.line + 1,
                    depth,
                });
                if (s.children) walk(s.children, depth + 1);
            }
        };
        walk(symbols || [], 0);
        return { path: uri.fsPath, symbols: flat };
    });

    // ── Workspace ──────────────────────────────────────────────────────
    server.on('workspace.folders', () => ({
        folders: (vscode.workspace.workspaceFolders || []).map((f) => f.uri.fsPath),
    }));

    server.on('workspace.search', async ({ pattern, maxResults = 50 }) => {
        const uris = await vscode.workspace.findFiles(pattern || '**/*', null, maxResults);
        return { files: uris.map((u) => u.fsPath) };
    });

    // ── Terminal (Kilo's execute_command: reuse + output capture) ──────
    server.on('terminal.run', async ({ command, name = 'Argos', cwd }) => {
        if (!command) throw new Error('missing command');
        const term =
            vscode.window.terminals.find((t) => t.name === name) ||
            vscode.window.createTerminal({
                name,
                cwd: cwd ? resolvePath(cwd) : vscode.workspace.workspaceFolders?.[0]?.uri,
            });
        term.show();

        // Shell integration (VS Code >= 1.93) lets us await the command and
        // capture output — but it needs a moment to initialize on a fresh
        // terminal, so poll briefly before falling back to blind sendText.
        let si = (term as any).shellIntegration;
        for (let i = 0; !si?.executeCommand && i < 30; i++) {
            await new Promise((r) => setTimeout(r, 100));
            si = (term as any).shellIntegration;
        }
        if (si?.executeCommand) {
            const exec = si.executeCommand(command);
            const reader = exec.read();
            let output = '';
            for await (const chunk of reader) output += chunk;
            return { executed: true, captured: true, output: stripAnsi(output) };
        }
        term.sendText(command);
        return { executed: true, captured: false };
    });

    // ── AI chat assistants ─────────────────────────────────────────────
    server.on('chat.providers', () => ({ providers: chat.providerStatus() }));

    server.on('chat.focus', ({ provider }) => chat.focusChat(provider));

    server.on('chat.new', ({ provider }) => chat.newChat(provider));

    server.on('chat.send', ({ text, provider, submit = true }) => {
        if (!text) throw new Error('missing text');
        return chat.sendToChat(String(text), provider, submit);
    });
}
