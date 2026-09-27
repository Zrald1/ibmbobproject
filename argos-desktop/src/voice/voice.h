#pragma once

#include <string>

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

// Explicit halves of the toggle for the Caps Lock hotkey / phone command:
// start_listen() is a no-op unless idle; stop_listen() stops recording and
// kicks off transcription (or cancels a silence-only take).
void start_listen();
void stop_listen();

bool listening();
// True while a recorded take is being transcribed (blocks new recordings).
bool transcribing();

// Speaks text aloud on a background thread while the robot plays its
// talking animation; sets the robot back to idle when done.
void speak_async(const std::string& text);
bool speaking();

// Purges the in-flight utterance (Esc). The speech thread unwinds normally:
// WaitUntilDone returns, talking animation stops, state goes back to idle.
void stop_speaking();

// Stop recording and join any in-flight transcription (called at shutdown).
void shutdown();

}  // namespace argos::voice
