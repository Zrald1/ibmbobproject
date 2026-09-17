#pragma once

// Voice pipeline — double-click the robot to talk to Argos.
//
// Toggle: first double-click starts waveIn mic capture (robot goes to the
// "listening" state), second stops it. The WAV is uploaded to AssemblyAI
// (REST /v2/upload + /v2/transcript poll), the transcript is fed to the
// Cerebras agent loop, and the robot animates through listening → thinking.

namespace argos::voice {

// Double-click handler: starts listening when idle, stops+transcribes when
// recording, ignored while a transcription is in flight.
void toggle_listen();

bool listening();

// Stop recording and join any in-flight transcription (called at shutdown).
void shutdown();

}  // namespace argos::voice
