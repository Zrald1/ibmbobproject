// Loopback HTTP bridge between the Argos desktop app and this editor.
//
// The server binds to 127.0.0.1 ONLY — it is unreachable from the network.
// Auth is a per-install bearer token. On start the bridge writes a discovery
// file to %APPDATA%\ArgosDesktop\ide-bridge.json containing { port, token };
// only processes running as this Windows user can read it.

import * as crypto from 'crypto';
import * as fs from 'fs';
import * as http from 'http';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';

export type Handler = (params: any) => Promise<any> | any;

const MAX_BODY = 8 * 1024 * 1024; // prompts + file payloads stay under this
const MAX_PORT_ATTEMPTS = 10;

function discoveryPath(): string {
    const appData =
        process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming');
    return path.join(appData, 'ArgosDesktop', 'ide-bridge.json');
}

export class BridgeServer {
    private server?: http.Server;
    private handlers = new Map<string, Handler>();
    private sseClients = new Set<http.ServerResponse>();
    private heartbeat?: NodeJS.Timeout;
    private token = '';
    private port = 0;

    constructor(private readonly context: vscode.ExtensionContext) {
        this.token = this.context.globalState.get<string>('argos.bridgeToken') || '';
        if (!this.token) {
            this.token = crypto.randomBytes(24).toString('hex');
            void this.context.globalState.update('argos.bridgeToken', this.token);
        }
    }

    get isRunning(): boolean {
        return !!this.server;
    }

    get portNumber(): number {
        return this.port;
    }

    on(method: string, handler: Handler): void {
        this.handlers.set(method, handler);
    }

    /** Push an event to every SSE listener (used for chat/progress streaming). */
    emit(event: string, data: unknown): void {
        const frame = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
        for (const res of this.sseClients) {
            res.write(frame);
        }
    }

    async start(): Promise<number> {
        if (this.server) return this.port;

        const base = vscode.workspace.getConfiguration('argos').get<number>('port', 47820);
        for (let attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
            const candidate = base + attempt;
            try {
                await this.listen(candidate);
                this.port = candidate;
                this.writeDiscoveryFile();
                this.heartbeat = setInterval(() => this.emit('heartbeat', { t: Date.now() }), 15000);
                return this.port;
            } catch (err: any) {
                if (err?.code !== 'EADDRINUSE') throw err;
            }
        }
        throw new Error(`Argos bridge: no free port in ${base}..${base + MAX_PORT_ATTEMPTS - 1}`);
    }

    stop(): void {
        if (this.heartbeat) clearInterval(this.heartbeat);
        for (const res of this.sseClients) res.end();
        this.sseClients.clear();
        this.server?.close();
        this.server = undefined;
        this.port = 0;
        try {
            fs.unlinkSync(discoveryPath());
        } catch {
            /* already gone */
        }
    }

    private listen(port: number): Promise<void> {
        return new Promise((resolve, reject) => {
            const server = http.createServer((req, res) => this.route(req, res));
            server.once('error', reject);
            server.listen(port, '127.0.0.1', () => {
                server.off('error', reject);
                this.server = server;
                resolve();
            });
        });
    }

    private route(req: http.IncomingMessage, res: http.ServerResponse): void {
        const url = new URL(req.url || '/', 'http://127.0.0.1');

        if (req.method === 'GET' && url.pathname === '/health') {
            return this.json(res, 200, { ok: true, name: 'argos-ide-bridge', version: this.context.extension.packageJSON.version });
        }

        if (!this.authorized(req)) {
            return this.json(res, 401, { ok: false, error: 'unauthorized' });
        }

        if (req.method === 'GET' && url.pathname === '/events') {
            return this.serveEvents(res);
        }
        if (req.method === 'POST' && url.pathname === '/command') {
            return this.serveCommand(req, res);
        }
        this.json(res, 404, { ok: false, error: 'not found' });
    }

    private authorized(req: http.IncomingMessage): boolean {
        const header = req.headers.authorization || '';
        return header === `Bearer ${this.token}`;
    }

    private serveEvents(res: http.ServerResponse): void {
        res.writeHead(200, {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            Connection: 'keep-alive',
        });
        res.write(`event: connected\ndata: {}\n\n`);
        this.sseClients.add(res);
        res.on('close', () => this.sseClients.delete(res));
    }

    private serveCommand(req: http.IncomingMessage, res: http.ServerResponse): void {
        const chunks: Buffer[] = [];
        let size = 0;
        req.on('data', (chunk: Buffer) => {
            size += chunk.length;
            if (size > MAX_BODY) {
                req.destroy();
                this.json(res, 413, { ok: false, error: 'payload too large' });
            } else {
                chunks.push(chunk);
            }
        });
        req.on('end', async () => {
            if (res.writableEnded) return;
            let body: any;
            try {
                body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
            } catch {
                return this.json(res, 400, { ok: false, error: 'invalid JSON' });
            }
            const handler = this.handlers.get(body?.method);
            if (!handler) {
                return this.json(res, 404, { ok: false, error: `unknown method: ${body?.method}` });
            }
            try {
                const result = await handler(body.params ?? {});
                this.json(res, 200, { ok: true, result });
            } catch (err: any) {
                this.json(res, 200, { ok: false, error: String(err?.message || err) });
            }
        });
        req.on('error', () => this.json(res, 500, { ok: false, error: 'request error' }));
    }

    private json(res: http.ServerResponse, status: number, payload: unknown): void {
        if (res.writableEnded) return;
        const body = JSON.stringify(payload);
        res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
        res.end(body);
    }

    private writeDiscoveryFile(): void {
        const file = discoveryPath();
        fs.mkdirSync(path.dirname(file), { recursive: true });
        const workspaceFolders = (vscode.workspace.workspaceFolders || []).map((f) => f.uri.fsPath);
        const info = {
            port: this.port,
            token: this.token,
            pid: process.pid,        // extension host process
            mainPid: process.ppid,   // IDE process that owns the window
            ide: vscode.env.appName,
            host: vscode.env.appHost,
            version: vscode.version,
            workspace: workspaceFolders[0] || '',
            workspaces: workspaceFolders,
            startedAt: new Date().toISOString(),
        };
        fs.writeFileSync(file, JSON.stringify(info, null, 2), 'utf8');
    }
}
