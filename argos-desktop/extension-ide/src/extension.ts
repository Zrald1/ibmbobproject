// Argos IDE Bridge — activation entry point.
//
// Starts the loopback bridge, registers the command surface, and keeps a
// status-bar item showing the port Argos is connected on.

import * as vscode from 'vscode';
import { BridgeServer } from './bridge';
import { registerHandlers } from './handlers';

let server: BridgeServer | undefined;
let statusItem: vscode.StatusBarItem | undefined;
let output: vscode.OutputChannel | undefined;

function setStatus(): void {
    if (!statusItem) return;
    if (server?.isRunning) {
        statusItem.text = `$(radio-tower) Argos: ${server.portNumber}`;
        statusItem.tooltip = 'Argos IDE bridge is listening on 127.0.0.1';
    } else {
        statusItem.text = '$(circle-slash) Argos: off';
        statusItem.tooltip = 'Argos IDE bridge is not running';
    }
    statusItem.show();
}

async function startBridge(context: vscode.ExtensionContext): Promise<void> {
    if (!server) server = new BridgeServer(context);
    registerHandlers(server);
    const port = await server.start();
    output?.appendLine(`[argos] bridge listening on 127.0.0.1:${port}`);
    setStatus();
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
    output = vscode.window.createOutputChannel('Argos Bridge');
    context.subscriptions.push(output);

    statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 90);
    statusItem.command = 'argos.showStatus';
    context.subscriptions.push(statusItem);
    setStatus();

    context.subscriptions.push(
        vscode.commands.registerCommand('argos.showStatus', () => {
            if (server?.isRunning) {
                void vscode.window.showInformationMessage(
                    `Argos bridge is listening on 127.0.0.1:${server.portNumber}`
                );
            } else {
                void vscode.window.showWarningMessage('Argos bridge is not running.');
            }
        }),
        vscode.commands.registerCommand('argos.restartBridge', async () => {
            server?.stop();
            await startBridge(context);
        }),
        vscode.workspace.onDidChangeConfiguration(async (e) => {
            if (e.affectsConfiguration('argos.port') && server) {
                server.stop();
                try {
                    await startBridge(context);
                } catch (err) {
                    output?.appendLine(`[argos] restart failed: ${err}`);
                }
            }
        })
    );

    const autostart = vscode.workspace.getConfiguration('argos').get<boolean>('autostart', true);
    if (autostart) {
        try {
            await startBridge(context);
        } catch (err) {
            output?.appendLine(`[argos] failed to start: ${err}`);
            setStatus();
        }
    }
}

export function deactivate(): void {
    server?.stop();
    server = undefined;
}
