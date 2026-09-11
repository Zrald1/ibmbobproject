/**
 * Argos App Simulation — faithful reproduction of the Android app's voice pipeline.
 *
 * Mirrors FloatingRobotService.java exactly:
 *   1. Double-tap (or hold mic) -> beginRecording()
 *   2. Capture mic at 16 kHz, mono, 16-bit PCM  (AudioRecord config)
 *   3. Wrap PCM in a 44-byte WAV header          (pcmToWav)
 *   4. POST multipart to /api/voice              (uploadVoiceToBackend)
 *      fields: file=audio.wav, screen_context, history
 *   5. Parse {"response": "..."}                 (onChatResponse / processVoiceRecording)
 *   6. Execute [TOOL:EXPR:...] / [TOOL:HAND:...]  (executeToolTags)
 *   7. Speak the tag-stripped text               (ttsSpeakJava)
 *   8. Robot animates with the expression
 *
 * Unlike the earlier mock, this records REAL audio and hits the REAL backend,
 * so AssemblyAI transcription + Cerebras qwen-3.8-27b are genuinely exercised.
 */

const STATES = { IDLE: 'idle', RECORDING: 'recording', THINKING: 'thinking', TALKING: 'talking' };

// Expression names copied verbatim from argos_robot.html EXPRESSIONS
const EXPRESSIONS = {
    NEUTRAL: 'neutral', HAPPY: 'happy', THINKING: 'thinking', TALKING: 'talking',
    SLEEPING: 'sleeping', SURPRISED: 'surprised', BLINK: 'blink', WINK: 'wink',
    LOVE: 'love', ANGRY: 'angry', SAD: 'sad', CONFUSED: 'confused', EXCITED: 'excited',
    DIZZY: 'dizzy', STAR_EYES: 'star_eyes', SCARED: 'scared', LAUGHING: 'laughing',
    HIDING_EYES: 'hiding_eyes', LISTENING: 'listening', RECORDING: 'recording'
};

// Strip [TOOL:...] tags the same way FloatingRobotService.stripToolTags() does,
// so nothing containing a colon is ever spoken ("TOOL colon EXPR colon HAPPY").
function stripToolTags(text) {
    if (!text) return '';
    return text
        // Chat role labels ("You:", "User:", "Argos:") must never be spoken
        .replace(/^\s*(you|user|argos|assistant|system)\s*:\s*/gim, ' ')
        .replace(/\b(you|user|argos|assistant|system)\s*:\s*/gi, ' ')
        .replace(/\[TOOL:EXPRSEQ:\[.*?\]\]/gs, ' ')
        .replace(/\[TOOL:[^\]]*\]/g, ' ')
        .replace(/\[?TOOL:[^\]\s]*\]?/gi, ' ')
        // Residual tag tokens that lost their brackets, e.g. "EXPR:HAPPY"
        .replace(/\b[A-Z][A-Z0-9_]{1,}\s*:\s*[A-Za-z0-9_./-]*/g, ' ')
        .replace(/\b(EXPRSEQ|EXPR|TOOL|HAND)\b/gi, ' ')
        .replace(/[\[\]{}]/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
}

const SILENCE_THRESHOLD = 800;      // matches SILENCE_THRESHOLD
const SILENCE_DURATION_MS = 4000;   // matches SILENCE_DURATION_MS
const MAX_RECORDING_DURATION_MS = 30000; // matches MAX_RECORDING_DURATION_MS

let currentState = STATES.IDLE;
let currentExpression = EXPRESSIONS.NEUTRAL;
let currentHandGesture = 'REST';
let backendUrl = 'http://13.229.100.183:8000';
let conversationHistory = [];       // matches conversationHistory

// ── Recording state ──
let mediaStream = null;
let audioCtx = null;
let scriptNode = null;
let sourceNode = null;
let recordedChunks = [];            // Int16Array pieces
let recordingStart = 0;
let lastSoundTime = 0;
let silenceTimer = null;
let maxTimer = null;
let isRecording = false;

// ── UI helpers ──
function $(id) { return document.getElementById(id); }

function setState(state) {
    currentState = state;
    const dot = $('statusDot');
    dot.className = 'status-dot ' + state;
    const labels = {
        idle: 'Idle — double-tap the robot (or press SPACE) to talk',
        recording: 'Recording… (speak now; auto-stops after 4s silence)',
        thinking: 'Thinking… (AssemblyAI → Cerebras)',
        talking: 'Speaking… (TTS)'
    };
    $('statusText').textContent = labels[state] || state;
}

function addMessage(role, text, extra) {
    const log = $('convoLog');
    const div = document.createElement('div');
    div.className = 'msg ' + (role === 'user' ? 'user' : 'argos');
    const who = role === 'user' ? 'You' : 'Argos';
    let html = `<div class="tag">${who}</div>${text}`;
    if (extra) html += `<div class="expr-badge">${extra}</div>`;
    div.innerHTML = html;
    log.appendChild(div);
    log.scrollTop = log.scrollHeight;
}

// ═══════════════════════════════════════════════════════════
// 1–3. Record mic -> 16 kHz mono 16-bit PCM -> WAV
// ═══════════════════════════════════════════════════════════
async function beginRecording() {
    if (isRecording) return;
    try {
        mediaStream = await navigator.mediaDevices.getUserMedia({
            audio: {
                channelCount: 1,
                echoCancellation: true,
                noiseSuppression: true
            }
        });
    } catch (e) {
        addMessage('argos', 'Microphone permission denied: ' + e.message);
        return;
    }

    isRecording = true;
    recordedChunks = [];
    recordingStart = Date.now();
    lastSoundTime = recordingStart;
    setState(STATES.RECORDING);
    setExpression(EXPRESSIONS.RECORDING || EXPRESSIONS.NEUTRAL);
    $('micBtn').classList.add('recording');
    $('micBtn').textContent = '⏹ Stop & Send';

    audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    sourceNode = audioCtx.createMediaStreamSource(mediaStream);
    // 4096-sample buffer ≈ 256 ms at 16 kHz — enough for amplitude checks
    scriptNode = audioCtx.createScriptProcessor(4096, 1, 1);

    scriptNode.onaudioprocess = (ev) => {
        if (!isRecording) return;
        const input = ev.inputBuffer.getChannelData(0);
        const pcm = floatTo16BitPCM(input);
        recordedChunks.push(pcm);

        // Silence detection — mirrors the AudioRecord amplitude loop
        let sum = 0;
        for (let i = 0; i < pcm.length; i++) sum += Math.abs(pcm[i]);
        const avgAmplitude = sum / pcm.length;
        if (avgAmplitude > SILENCE_THRESHOLD) lastSoundTime = Date.now();

        const elapsed = Date.now() - recordingStart;
        const silenceElapsed = Date.now() - lastSoundTime;
        if (silenceElapsed >= SILENCE_DURATION_MS && elapsed > 2000) {
            addMessage('argos', '(auto: silence detected)', null);
            stopRecording();
        } else if (elapsed >= MAX_RECORDING_DURATION_MS) {
            stopRecording();
        }
    };

    sourceNode.connect(scriptNode);
    scriptNode.connect(audioCtx.destination); // required to pull audio on some browsers
}

function floatTo16BitPCM(float32) {
    const out = new Int16Array(float32.length);
    for (let i = 0; i < float32.length; i++) {
        const s = Math.max(-1, Math.min(1, float32[i]));
        out[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
    }
    return out;
}

function stopRecording() {
    if (!isRecording) return;
    isRecording = false;
    $('micBtn').classList.remove('recording');
    $('micBtn').textContent = '🎤 Hold to Talk';

    try { scriptNode.disconnect(); } catch (e) {}
    try { sourceNode.disconnect(); } catch (e) {}
    try { audioCtx.close(); } catch (e) {}
    if (mediaStream) mediaStream.getTracks().forEach(t => t.stop());
    scriptNode = null; sourceNode = null; audioCtx = null; mediaStream = null;

    // Flatten PCM
    let total = 0;
    for (const c of recordedChunks) total += c.length;
    const pcm = new Int16Array(total);
    let off = 0;
    for (const c of recordedChunks) { pcm.set(c, off); off += c.length; }

    if (pcm.length < 160) { // < 10 ms, same guard as the app
        addMessage('argos', 'Voice: No audio captured');
        setState(STATES.IDLE);
        return;
    }

    const wav = pcmToWav(pcm, 16000, 1, 16);
    uploadVoiceToBackend(wav);
}

/**
 * pcmToWav — byte-for-byte port of FloatingRobotService.pcmToWav().
 * 44-byte RIFF/WAVE header followed by raw PCM.
 */
function pcmToWav(pcmData, sampleRate, channels, bitsPerSample) {
    const dataSize = pcmData.length * 2;
    const byteRate = sampleRate * channels * bitsPerSample / 8;
    const blockAlign = channels * bitsPerSample / 8;
    const chunkSize = 36 + dataSize;
    const wav = new ArrayBuffer(44 + dataSize);
    const dv = new DataView(wav);

    writeStr(dv, 0, 'RIFF');
    dv.setUint32(4, chunkSize, true);
    writeStr(dv, 8, 'WAVE');
    writeStr(dv, 12, 'fmt ');
    dv.setUint32(16, 16, true);          // subchunk size
    dv.setUint16(20, 1, true);           // audio format = PCM
    dv.setUint16(22, channels, true);
    dv.setUint32(24, sampleRate, true);
    dv.setUint32(28, byteRate, true);
    dv.setUint16(32, blockAlign, true);
    dv.setUint16(34, bitsPerSample, true);
    writeStr(dv, 36, 'data');
    dv.setUint32(40, dataSize, true);

    let p = 44;
    for (let i = 0; i < pcmData.length; i++) {
        dv.setInt16(p, pcmData[i], true);
        p += 2;
    }
    return new Blob([wav], { type: 'audio/wav' });
}

function writeStr(dv, offset, str) {
    for (let i = 0; i < str.length; i++) dv.setUint8(offset + i, str.charCodeAt(i));
}

// ═══════════════════════════════════════════════════════════
// 4–5. POST multipart to /api/voice  (uploadVoiceToBackend)
// ═══════════════════════════════════════════════════════════
async function uploadVoiceToBackend(wavBlob) {
    setState(STATES.THINKING);
    const startTime = performance.now();

    const form = new FormData();
    form.append('file', wavBlob, 'audio.wav');
    // screen_context omitted (privacy mode / no accessibility service)
    if (conversationHistory.length > 0) {
        form.append('history', JSON.stringify(conversationHistory));
    }

    try {
        const resp = await fetch(backendUrl + '/api/voice', { method: 'POST', body: form });
        const bodyText = await resp.text();
        const elapsed = Math.round(performance.now() - startTime);

        if (!resp.ok) {
            let reason = bodyText;
            try { const j = JSON.parse(bodyText); reason = j.detail || j.error || reason; } catch (e) {}
            addMessage('argos', 'Argos error: HTTP ' + resp.status + ': ' + reason);
            setState(STATES.IDLE);
            return;
        }

        const json = JSON.parse(bodyText);
        // Mirror the app's field handling: "response" is the real field,
        // "reply" is the legacy fallback.
        let reply = json.response || json.reply || '';
        const errMsg = json.error || json.detail || null;
        if (errMsg) {
            addMessage('argos', 'Argos error: ' + errMsg);
            setState(STATES.IDLE);
            return;
        }
        if (!reply) {
            addMessage('argos', 'Argos: (no reply from AI — please try again)');
            setState(STATES.IDLE);
            return;
        }

        console.log(`Backend replied in ${elapsed} ms`);
        displayAndSpeakVoiceReply(reply, null);
    } catch (e) {
        addMessage('argos', 'Argos error: ' + e.message);
        setState(STATES.IDLE);
    }
}

// ═══════════════════════════════════════════════════════════
// 6–8. Tag execution, display, TTS  (displayAndSpeakVoiceReply)
// ═══════════════════════════════════════════════════════════
function displayAndSpeakVoiceReply(rawReply, userMessage) {
    // Strip [TOOL:...] for display + speech
    const clean = stripToolTags(rawReply);

    executeToolTags(rawReply);
    if (!/\[TOOL:EXPR:/.test(rawReply) && !/\[TOOL:EXPRSEQ:/.test(rawReply)) {
        setExpression(EXPRESSIONS.NEUTRAL);
    }
    if (!/\[TOOL:HAND:/.test(rawReply)) setHandGesture('REST');

    if (clean) {
        addMessage('argos', clean, 'expr: ' + currentExpression + (currentHandGesture !== 'REST' ? ' · hand: ' + currentHandGesture : ''));
        conversationHistory.push({ role: 'assistant', content: rawReply });
    } else {
        addMessage('argos', '(tool executed, see results above)');
    }

    if (clean) speakText(clean);
    else setState(STATES.IDLE);
}

/** executeToolTags — mirrors FloatingRobotService.executeToolTags() for JS-side tags. */
function executeToolTags(response) {
    const re = /\[TOOL:([^\]]+)\]/g;
    let m;
    while ((m = re.exec(response)) !== null) {
        executeTool(m[1].trim());
    }
}

function executeTool(tool) {
    if (tool.startsWith('EXPR:')) {
        const name = tool.substring(5).trim().toUpperCase();
        if (EXPRESSIONS[name]) setExpression(EXPRESSIONS[name]);
    } else if (tool.startsWith('HAND:')) {
        setHandGesture(tool.substring(5).trim().toUpperCase());
    } else if (tool === 'LOOK') {
        // robot looks at user — no-op visually here
    } else if (tool.startsWith('EXPRSEQ:')) {
        // Timed expression sequence
        try {
            const seq = JSON.parse(tool.substring(8).trim());
            playExpressionSequence(seq);
        } catch (e) {}
    }
    // Other tools (OPEN, TYPE, SEARCH, file tools…) are Android-only; ignored here.
}

let seqTimer = null;
function playExpressionSequence(seq) {
    if (seqTimer) clearTimeout(seqTimer);
    let i = 0;
    const step = () => {
        if (i >= seq.length) return;
        const item = seq[i];
        const name = String(item.expr || '').toUpperCase();
        if (EXPRESSIONS[name]) setExpression(EXPRESSIONS[name]);
        i++;
        seqTimer = setTimeout(step, (item.duration || 1.0) * 1000);
    };
    step();
}

function setExpression(name) {
    currentExpression = name;
}

function setHandGesture(name) {
    currentHandGesture = name;
}

// ═══════════════════════════════════════════════════════════
// TTS  (ttsSpeakJava)
// ═══════════════════════════════════════════════════════════
function speakText(text) {
    if (!window.speechSynthesis) { setState(STATES.IDLE); return; }
    window.speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(text);
    u.rate = 1.0; u.pitch = 1.1; u.volume = 1.0;
    u.onstart = () => { setState(STATES.TALKING); $('stopBtn').style.display = 'inline-block'; };
    u.onend = () => { setState(STATES.IDLE); $('stopBtn').style.display = 'none'; };
    u.onerror = () => { setState(STATES.IDLE); $('stopBtn').style.display = 'none'; };
    window.speechSynthesis.speak(u);
}

function stopSpeaking() {
    if (window.speechSynthesis) window.speechSynthesis.cancel();
    setState(STATES.IDLE);
    $('stopBtn').style.display = 'none';
}

// ═══════════════════════════════════════════════════════════
// Robot face renderer — mirrors argos_robot.html expressions
// ═══════════════════════════════════════════════════════════
const canvas = $('robotCanvas');
const ctx = canvas.getContext('2d');
const W = canvas.width, H = canvas.height, cx = W / 2, cy = H / 2 - 10;
let animTime = 0;

const EXPR_COLORS = {
    neutral: '#00e5cc', happy: '#00ff88', thinking: '#ffdd00', talking: '#00e5cc',
    surprised: '#ffaa00', sad: '#4488ff', angry: '#ff4444', love: '#ff66aa',
    excited: '#ff8800', confused: '#aa44ff', sleeping: '#444466',
    laughing: '#00ff88', wink: '#00e5cc', scared: '#ffffff',
    star_eyes: '#ffdd00', dizzy: '#aa88ff', hiding_eyes: '#8888aa', blink: '#00e5cc',
    recording: '#ff3333', listening: '#00e5cc'
};

function exprColor() { return EXPR_COLORS[currentExpression] || '#00e5cc'; }

function drawRobot() {
    ctx.clearRect(0, 0, W, H);
    animTime += 0.016;
    const bob = Math.sin(animTime * 1.8) * 8;
    const talking = currentState === STATES.TALKING;
    const thinking = currentState === STATES.THINKING;
    const recording = currentState === STATES.RECORDING;
    const color = recording ? '#ff3333' : exprColor();
    const R = 120;

    // glow rings
    const pulse = 1 + Math.sin(animTime * 2.5) * 0.08;
    ctx.strokeStyle = color + '40'; ctx.lineWidth = 3;
    ctx.beginPath(); ctx.arc(cx, cy + bob, R * 1.3 * pulse, 0, Math.PI * 2); ctx.stroke();
    ctx.strokeStyle = color + '25'; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.arc(cx, cy + bob, R * 1.15 * pulse, 0, Math.PI * 2); ctx.stroke();

    // head
    const g = ctx.createRadialGradient(cx - 30, cy + bob - 30, 10, cx, cy + bob, R);
    g.addColorStop(0, '#1a1a2e'); g.addColorStop(0.5, '#0a0a14'); g.addColorStop(1, '#050508');
    ctx.fillStyle = g;
    ctx.beginPath(); ctx.arc(cx, cy + bob, R, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = color + '60'; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.arc(cx, cy + bob, R, 0, Math.PI * 2); ctx.stroke();

    // eyes
    const eyeY = cy + bob - 20, sp = 50;
    let ex = 18, ey = 18;
    if (currentExpression === 'sleeping') ey = 2;
    if (currentExpression === 'happy' || currentExpression === 'laughing') ey = 14;
    if (currentExpression === 'surprised' || currentExpression === 'excited') { ex = 22; ey = 22; }
    if (currentExpression === 'sad') ey = 12;
    if (currentExpression === 'wink') ey = 2;
    if (currentExpression === 'angry') ey = 10;
    if (currentExpression === 'blink') ey = 3;

    if (currentExpression === 'love') {
        heart(cx - sp, eyeY, 20, '#ff66aa'); heart(cx + sp, eyeY, 20, '#ff66aa');
    } else if (currentExpression === 'star_eyes') {
        star(cx - sp, eyeY, 18, '#ffdd00'); star(cx + sp, eyeY, 18, '#ffdd00');
    } else {
        ctx.shadowBlur = 20 * (talking ? 1.3 : thinking ? 0.7 : 1);
        ctx.shadowColor = color; ctx.fillStyle = color;
        ctx.beginPath(); ctx.ellipse(cx - sp, eyeY, ex, ey, 0, 0, Math.PI * 2); ctx.fill();
        const ry = currentExpression === 'wink' ? 2 : ey;
        ctx.beginPath(); ctx.ellipse(cx + sp, eyeY, ex, ry, 0, 0, Math.PI * 2); ctx.fill();
        ctx.shadowBlur = 0;
    }

    // thinking dots
    if (thinking) {
        for (let i = 0; i < 3; i++) {
            const phase = Math.floor(animTime * 3) % 3;
            const dy = cy + bob + 40 + (i === phase ? -8 : 0);
            ctx.fillStyle = `rgba(255,221,0,${i === phase ? 1 : 0.3})`;
            ctx.shadowBlur = 10; ctx.shadowColor = '#ffdd00';
            ctx.beginPath(); ctx.arc(cx - 30 + i * 30, dy, 6, 0, Math.PI * 2); ctx.fill();
            ctx.shadowBlur = 0;
        }
    }

    // mouth
    const my = cy + bob + 40;
    ctx.strokeStyle = color; ctx.lineWidth = 4;
    ctx.shadowBlur = 15; ctx.shadowColor = color;
    if (talking) {
        const open = Math.abs(Math.sin(animTime * 12)) * 25 + 5;
        ctx.fillStyle = color + '40';
        ctx.beginPath(); ctx.ellipse(cx, my, 30, open, 0, 0, Math.PI * 2); ctx.fill();
        ctx.beginPath(); ctx.ellipse(cx, my, 30, open, 0, 0, Math.PI * 2); ctx.stroke();
    } else if (currentExpression === 'sleeping') {
        ctx.beginPath(); ctx.moveTo(cx - 20, my); ctx.lineTo(cx + 20, my); ctx.stroke();
    } else if (currentExpression === 'happy' || currentExpression === 'laughing') {
        ctx.beginPath(); ctx.arc(cx, my - 10, 25, 0.2, Math.PI - 0.2); ctx.stroke();
    } else if (currentExpression === 'sad') {
        ctx.beginPath(); ctx.arc(cx, my + 15, 25, Math.PI + 0.2, -0.2); ctx.stroke();
    } else if (currentExpression === 'surprised' || currentExpression === 'excited') {
        ctx.beginPath(); ctx.arc(cx, my, 15, 0, Math.PI * 2); ctx.stroke();
    } else {
        ctx.beginPath(); ctx.arc(cx, my - 5, 18, 0.3, Math.PI - 0.3); ctx.stroke();
    }
    ctx.shadowBlur = 0;

    // antenna
    ctx.strokeStyle = '#333'; ctx.lineWidth = 3;
    ctx.beginPath(); ctx.moveTo(cx, cy + bob - R); ctx.lineTo(cx, cy + bob - R - 25); ctx.stroke();
    const tip = recording ? 1 + Math.sin(animTime * 8) * 0.3 : 1;
    ctx.fillStyle = recording ? '#ff4444' : color;
    ctx.shadowBlur = 15; ctx.shadowColor = recording ? '#ff4444' : color;
    ctx.beginPath(); ctx.arc(cx, cy + bob - R - 28, 8 * tip, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;

    // recording ring
    if (recording) {
        ctx.strokeStyle = `rgba(255,68,68,${0.3 + Math.sin(animTime * 6) * 0.2})`;
        ctx.lineWidth = 4;
        ctx.beginPath(); ctx.arc(cx, cy + bob, R * 1.4 * (1 + Math.sin(animTime * 6) * 0.2), 0, Math.PI * 2); ctx.stroke();
    }

    // label
    ctx.fillStyle = '#666'; ctx.font = '14px sans-serif'; ctx.textAlign = 'center';
    ctx.fillText(currentExpression + (currentHandGesture !== 'REST' ? '  ·  ' + currentHandGesture : ''), cx, H - 16);
}

function heart(x, y, s, c) {
    ctx.fillStyle = c; ctx.shadowBlur = 15; ctx.shadowColor = c;
    ctx.beginPath();
    ctx.moveTo(x, y + s * 0.3);
    ctx.bezierCurveTo(x, y - s * 0.2, x - s, y - s * 0.2, x - s, y + s * 0.2);
    ctx.bezierCurveTo(x - s, y + s * 0.6, x, y + s * 0.8, x, y + s);
    ctx.bezierCurveTo(x, y + s * 0.8, x + s, y + s * 0.6, x + s, y + s * 0.2);
    ctx.bezierCurveTo(x + s, y - s * 0.2, x, y - s * 0.2, x, y + s * 0.3);
    ctx.fill(); ctx.shadowBlur = 0;
}

function star(x, y, s, c) {
    ctx.fillStyle = c; ctx.shadowBlur = 15; ctx.shadowColor = c;
    ctx.beginPath();
    for (let i = 0; i < 10; i++) {
        const a = (i * Math.PI) / 5 - Math.PI / 2;
        const r = i % 2 === 0 ? s : s * 0.4;
        const px = x + Math.cos(a) * r, py = y + Math.sin(a) * r;
        i === 0 ? ctx.moveTo(px, py) : ctx.lineTo(px, py);
    }
    ctx.closePath(); ctx.fill(); ctx.shadowBlur = 0;
}

function animate() {
    try { drawRobot(); } catch (e) { console.error(e); }
    requestAnimationFrame(animate);
}
animate();

// ═══════════════════════════════════════════════════════════
// Input: double-tap robot / hold mic / SPACE  (onHeadTap)
// ═══════════════════════════════════════════════════════════
let lastTap = 0;
canvas.addEventListener('click', () => {
    const now = Date.now();
    if (now - lastTap < 350) { lastTap = 0; toggleVoice(); }
    else lastTap = now;
});

$('micBtn').addEventListener('click', toggleVoice);
$('stopBtn').addEventListener('click', stopSpeaking);

document.addEventListener('keydown', (e) => {
    if (e.code === 'Space' && currentState !== STATES.THINKING) {
        e.preventDefault(); toggleVoice();
    }
});

function toggleVoice() {
    if (currentState === STATES.TALKING) { stopSpeaking(); return; }
    if (isRecording) { stopRecording(); return; }
    if (currentState === STATES.THINKING) return;
    backendUrl = $('backendUrl').value.trim();
    beginRecording();
}

setState(STATES.IDLE);
console.log('Argos simulation ready — faithful reproduction of the app voice pipeline.');
