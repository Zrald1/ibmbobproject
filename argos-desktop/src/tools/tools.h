#pragma once

// Argos tool executor — the capabilities an AI agent loop calls directly,
// WITHOUT going through the IDE's chatbox.
//
// Two backends:
//   bridge — when an editor runs the extension-ide bridge, calls go through it
//            so open buffers stay in sync and edits are undoable (WorkspaceEdit).
//   native — plain Win32/std::filesystem, works with no IDE at all.
//
// File tools accept absolute paths anywhere on disk (inside or outside the
// workspace) or paths relative to the IDE workspace / current directory.
//
// Modelled on the tool sets of OpenCode (bash/edit/write/grep/glob) and
// Kilo Code (read_file/apply_diff/search_files/execute_command).

#include <string>
#include <string_view>

#include <nlohmann/json.hpp>

namespace argos::tools {

struct Result {
    bool ok = false;
    std::string output;  // human/model-readable summary or error
    nlohmann::json data; // structured payload (file text, matches, ...)
};

// OpenAI/Cerebras-format function schemas describing every tool below.
// Feed straight into chat.completions `tools`.
const nlohmann::json& schemas();

// Execute one tool call. `args` is the model-supplied arguments object.
Result execute(std::string_view tool, const nlohmann::json& args);

}  // namespace argos::tools
