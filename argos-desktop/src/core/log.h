#pragma once

// Tiny thread-safe logger: rolling daily file in %APPDATA%\ArgosDesktop\logs
// plus an in-memory ring buffer the UI can display.

#include <deque>
#include <mutex>
#include <string>
#include <string_view>

namespace argos::log {

enum class Level { Debug, Info, Warn, Error };

void init();
void write(Level level, std::string_view message);

// Most recent lines (newest last), for the in-app log view.
std::deque<std::string> recent(size_t max_lines = 300);

inline void debug(std::string_view m) { write(Level::Debug, m); }
inline void info(std::string_view m) { write(Level::Info, m); }
inline void warn(std::string_view m) { write(Level::Warn, m); }
inline void error(std::string_view m) { write(Level::Error, m); }

}  // namespace argos::log
