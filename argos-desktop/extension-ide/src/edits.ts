// SEARCH/REPLACE block engine — the same edit format used by Kilo Code,
// Roo Code and Aider:
//
//   <<<<<<< SEARCH
//   :start_line:12
//   -------
//   exact content to find
//   =======
//   new content
//   >>>>>>> REPLACE
//
// Matching strategy (mirrors Kilo's apply_diff):
//   1. Exact unique substring match.
//   2. Multiple exact matches -> the :start_line: hint picks the nearest one.
//   3. Fallback: whitespace-normalised fuzzy line match (>= 90% line equality),
//      searched middle-out from the hint.
//
// Blocks apply sequentially against the evolving document; each failure is
// reported with the block number so the caller can retry with fresh context.

export interface ReplaceBlock {
    index: number;
    startLine?: number; // 1-based hint inside the original file
    search: string;
    replace: string;
}

export interface ApplyResult {
    applied: number;
    failures: Array<{ block: number; reason: string }>;
    content: string;
}

export function parseBlocks(diff: string): ReplaceBlock[] {
    const lines = diff.split(/\r?\n/);
    const blocks: ReplaceBlock[] = [];
    let i = 0;
    while (i < lines.length) {
        if (!/^<{7}\s*SEARCH/.test(lines[i])) {
            i++;
            continue;
        }
        i++;
        let startLine: number | undefined;
        if (i < lines.length && /^\s*:?start_line:?\s*\d+/.test(lines[i])) {
            const m = lines[i].match(/\d+/);
            startLine = m ? parseInt(m[0], 10) : undefined;
            i++;
        }
        if (i < lines.length && /^-{7}/.test(lines[i])) i++; // optional -------
        const searchLines: string[] = [];
        while (i < lines.length && !/^={7}/.test(lines[i])) searchLines.push(lines[i++]);
        if (i < lines.length) i++; // =======
        const replaceLines: string[] = [];
        while (i < lines.length && !/^>{7}\s*REPLACE/.test(lines[i])) replaceLines.push(lines[i++]);
        if (i < lines.length) i++; // >>>>>>> REPLACE
        blocks.push({
            index: blocks.length + 1,
            startLine,
            search: searchLines.join('\n'),
            replace: replaceLines.join('\n'),
        });
    }
    return blocks;
}

function normalize(line: string): string {
    return line.trim().replace(/\s+/g, ' ');
}

/** 0-based [start, end) line range matching `search`, or -1 / 'ambiguous'. */
function fuzzyFind(fileLines: string[], search: string, hint?: number): { start: number; end: number } | 'ambiguous' | null {
    const needle = search.split(/\r?\n/).map(normalize);
    if (needle.length === 0 || needle.length > fileLines.length) return null;

    const hay = fileLines.map(normalize);
    const order: number[] = [];
    if (hint !== undefined) {
        const center = Math.max(0, Math.min(hint - 1, fileLines.length - needle.length));
        order.push(center);
        for (let d = 1; d <= fileLines.length; d++) {
            if (center - d >= 0) order.push(center - d);
            if (center + d <= fileLines.length - needle.length) order.push(center + d);
        }
    } else {
        for (let i = 0; i <= fileLines.length - needle.length; i++) order.push(i);
    }

    let bestStart = -1;
    let bestScore = 0;
    let ties = 0;
    for (const start of order) {
        let equal = 0;
        for (let j = 0; j < needle.length; j++) {
            if (hay[start + j] === needle[j]) equal++;
        }
        const score = equal / needle.length;
        if (score > bestScore) {
            bestScore = score;
            bestStart = start;
            ties = 1;
        } else if (score === bestScore) {
            ties++;
        }
        if (bestScore === 1 && ties === 1) break;
    }
    if (bestScore < 0.9 || bestStart < 0) return null;
    if (ties > 1) return 'ambiguous';
    return { start: bestStart, end: bestStart + needle.length };
}

export function applyBlocks(content: string, blocks: ReplaceBlock[]): ApplyResult {
    let applied = 0;
    const failures: ApplyResult['failures'] = [];

    for (const block of blocks) {
        if (block.search.length === 0) {
            failures.push({ block: block.index, reason: 'empty SEARCH content' });
            continue;
        }

        // 1-2. Exact match, disambiguated by the line hint when needed.
        const hits: number[] = [];
        let at = content.indexOf(block.search);
        while (at !== -1) {
            hits.push(at);
            at = content.indexOf(block.search, at + 1);
        }

        if (hits.length === 1) {
            content =
                content.slice(0, hits[0]) + block.replace + content.slice(hits[0] + block.search.length);
            applied++;
            continue;
        }
        if (hits.length > 1 && block.startLine !== undefined) {
            const lineOf = (off: number) => content.slice(0, off).split('\n').length;
            hits.sort((a, b) => Math.abs(lineOf(a) - block.startLine!) - Math.abs(lineOf(b) - block.startLine!));
            content =
                content.slice(0, hits[0]) + block.replace + content.slice(hits[0] + block.search.length);
            applied++;
            continue;
        }

        // 3. Fuzzy fallback on normalised lines.
        const lines = content.split(/\r?\n/);
        const match = fuzzyFind(lines, block.search, block.startLine);
        if (match === 'ambiguous') {
            failures.push({ block: block.index, reason: 'SEARCH matched multiple locations; add :start_line: or more context' });
            continue;
        }
        if (!match) {
            failures.push({ block: block.index, reason: 'SEARCH content not found (exact or fuzzy)' });
            continue;
        }
        const eol = content.includes('\r\n') ? '\r\n' : '\n';
        lines.splice(match.start, match.end - match.start, ...block.replace.split(/\r?\n/));
        content = lines.join(eol);
        applied++;
    }

    return { applied, failures, content };
}
