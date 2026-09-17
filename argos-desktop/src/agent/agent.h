#pragma once

// Cerebras agent loop — the brain behind chat, task prompts and (later)
// proactive behaviour.
//
// One turn: user text → POST /v1/chat/completions (with tools::schemas()) →
// if the model returns tool_calls, execute each via tools::execute() and feed
// results back as role:"tool" messages → repeat until the model answers or
// the round cap is hit. gpt-oss-120b does not support parallel tool calls,
// so parallel_tool_calls is disabled and calls run sequentially.
//
// Two entry points:
//   ask_async()  — Chat tab: runs the turn on a worker thread
//   ask_sync()   — phone/relay dispatch: runs inline, returns the reply

#include <atomic>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include <nlohmann/json.hpp>

namespace argos::agent {

struct ChatEntry {
    std::string role;  // "user" | "assistant" | "tool" | "error"
    std::string text;
};

class Agent {
public:
    // True while a turn is running on the worker thread.
    bool busy() const { return busy_; }

    // True when a Cerebras API key is configured.
    bool ready() const;

    // Snapshot of the visible chat history for the UI.
    std::vector<ChatEntry> history() const;

    // Queue a turn on a worker thread. False if busy, no key, or empty text.
    bool ask_async(const std::string& text);

    // Run a turn inline (blocks for as long as the model needs — the phone
    // and relay callers already run on their own threads).
    // Returns {ok, reply-or-error}.
    std::pair<bool, std::string> ask_sync(const std::string& text);

    void clear();

private:
    // Shared core: appends the user message, runs the tool-call loop, and
    // records visible entries. Must NOT be called with mu_ held.
    std::pair<bool, std::string> run_turn(const std::string& text);

    // One POST to /chat/completions. Returns the parsed response body.
    std::optional<nlohmann::json> chat_complete(const nlohmann::json& messages,
                                                std::string* err);

    void push_visible(std::string role, std::string text);
    void trim_context();

    mutable std::mutex mu_;
    nlohmann::json messages_ = nlohmann::json::array();  // full API context
    std::vector<ChatEntry> visible_;
    std::atomic<bool> busy_{false};
};

}  // namespace argos::agent
