// Drivers for the AI chat assistants that can live inside the editor.
//
// Two delivery paths:
//   'command'    – a real VS Code command accepted the prompt (Copilot).
//   'needs-paste'– no programmatic input exists; we focused the chat and put
//                  the prompt on the OS clipboard, so the caller (Argos) must
//                  SendInput Ctrl+V then Enter. This works for webview-based
//                  chats (Continue, Cline, Cody, Cursor/Windsurf panes).

import * as vscode from 'vscode';

interface Step {
    command: string;
    args?: (text: string) => any;
}

export interface ChatProvider {
    id: string;
    name: string;
    extensionIds: string[];
    focusCommands: string[];
    sendPlans: Step[][];
    newCommands: string[];
}

const PROVIDERS: ChatProvider[] = [
    {
        id: 'copilot',
        name: 'GitHub Copilot Chat',
        extensionIds: ['github.copilot-chat'],
        focusCommands: [
            'workbench.panel.chat.view.copilot.focus',
            'workbench.action.chat.open',
        ],
        sendPlans: [
            // Newer builds: open with apply submits the query directly.
            [{ command: 'workbench.action.chat.open', args: (t) => ({ query: t, apply: true }) }],
            // Older builds: open with a prefilled query, then submit.
            [
                { command: 'workbench.action.chat.open', args: (t) => ({ query: t, isPartialQuery: true }) },
                { command: 'workbench.action.chat.submit' },
            ],
            // Oldest signature takes the query as a plain string.
            [{ command: 'workbench.action.chat.open', args: (t) => t }],
        ],
        newCommands: ['workbench.action.chat.newChat', 'workbench.action.chat.clear'],
    },
    {
        id: 'continue',
        name: 'Continue',
        extensionIds: ['Continue.continue'],
        focusCommands: [
            'continue.focusContinueInputWithoutClear',
            'continue.focusContinueInput',
        ],
        sendPlans: [],
        newCommands: ['continue.newSession'],
    },
    {
        id: 'cline',
        name: 'Cline',
        extensionIds: ['saoudrizwan.claude-dev'],
        focusCommands: ['cline.focusChatInput', 'workbench.view.extension.cline-ActivityBar'],
        sendPlans: [],
        newCommands: ['cline.plusButtonClicked'],
    },
    {
        id: 'roo',
        name: 'Roo Code',
        extensionIds: ['RooVeterinaryInc.roo-cline'],
        focusCommands: ['roo-cline.focusChatInput', 'workbench.view.extension.roo-cline-ActivityBar'],
        sendPlans: [],
        newCommands: ['roo-cline.plusButtonClicked'],
    },
    {
        id: 'cody',
        name: 'Sourcegraph Cody',
        extensionIds: ['sourcegraph.cody-ai'],
        focusCommands: ['cody.chat.focus', 'cody.chat.panel.focus'],
        sendPlans: [],
        newCommands: ['cody.chat.newChat'],
    },
    {
        // Windsurf Cascade / Devin's built-in assistant — not an extension,
        // detected via appName in pickProvider.
        id: 'cascade',
        name: 'Cascade (Windsurf/Devin)',
        extensionIds: [],
        focusCommands: [
            'windsurf.openCascade',
            'workbench.action.chat.open',
            'workbench.panel.chat',
        ],
        sendPlans: [],
        newCommands: [],
    },
    {
        // Any other assistant pane (Tabnine, Amazon Q, custom webviews, ...).
        id: 'generic',
        name: 'Generic chat view',
        extensionIds: [],
        focusCommands: ['workbench.action.chat.open', 'workbench.panel.chat'],
        sendPlans: [],
        newCommands: [],
    },
];

export function installedProviders(): ChatProvider[] {
    return PROVIDERS.filter(
        (p) =>
            p.extensionIds.length === 0 ||
            p.extensionIds.some((id) => vscode.extensions.getExtension(id) !== undefined)
    );
}

export function providerStatus() {
    return PROVIDERS.map((p) => ({
        id: p.id,
        name: p.name,
        installed: p.extensionIds.some((id) => vscode.extensions.getExtension(id) !== undefined),
        programmaticSend: p.sendPlans.length > 0,
    }));
}

function pickProvider(requested?: string): ChatProvider | undefined {
    const wanted =
        requested && requested !== 'auto'
            ? requested
            : vscode.workspace.getConfiguration('argos').get<string>('chatProvider', 'auto');
    const installed = installedProviders();
    if (wanted && wanted !== 'auto') {
        return PROVIDERS.find((p) => p.id === wanted);
    }
    // auto: built-in Cascade in Windsurf/Devin forks, then first installed
    // provider extension, else generic.
    if (/windsurf|devin/i.test(vscode.env.appName)) {
        return PROVIDERS.find((p) => p.id === 'cascade');
    }
    return installed.find((p) => p.extensionIds.length > 0) || PROVIDERS.find((p) => p.id === 'generic');
}

async function trySteps(commands: string[] | Step[][], text = ''): Promise<boolean> {
    const plans: Step[][] =
        typeof commands[0] === 'string'
            ? (commands as string[]).map((c) => [{ command: c }])
            : (commands as Step[][]);
    for (const plan of plans) {
        let ok = true;
        for (const step of plan) {
            try {
                await vscode.commands.executeCommand(
                    step.command,
                    ...(step.args ? [step.args(text)] : [])
                );
            } catch {
                ok = false;
                break;
            }
        }
        if (ok) return true;
    }
    return false;
}

export async function focusChat(providerId?: string): Promise<{ provider: string; focused: boolean }> {
    const provider = pickProvider(providerId);
    if (!provider) throw new Error('no chat provider available');
    const focused = await trySteps(provider.focusCommands);
    return { provider: provider.id, focused };
}

export async function newChat(providerId?: string): Promise<{ provider: string; ok: boolean }> {
    const provider = pickProvider(providerId);
    if (!provider) throw new Error('no chat provider available');
    const ok = await trySteps(provider.newCommands);
    return { provider: provider.id, ok };
}

export async function sendToChat(
    text: string,
    providerId?: string,
    submit = true
): Promise<{ provider: string; delivered: 'command' | 'needs-paste'; detail?: string }> {
    const provider = pickProvider(providerId);
    if (!provider) throw new Error('no chat provider available');

    for (const plan of provider.sendPlans) {
        const steps = submit ? plan : plan.slice(0, 1);
        let ok = true;
        for (const step of steps) {
            try {
                await vscode.commands.executeCommand(
                    step.command,
                    ...(step.args ? [step.args(text)] : [])
                );
            } catch {
                ok = false;
                break;
            }
        }
        if (ok) return { provider: provider.id, delivered: 'command' };
    }

    // Fallback: focus the chat, stage the prompt on the clipboard; the desktop
    // app finishes with a synthetic Ctrl+V / Enter.
    await trySteps(provider.focusCommands);
    await vscode.env.clipboard.writeText(text);
    return {
        provider: provider.id,
        delivered: 'needs-paste',
        detail: 'prompt is on the clipboard; send Ctrl+V then Enter via SendInput',
    };
}
