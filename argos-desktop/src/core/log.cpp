#include "core/log.h"

#include <windows.h>

#include <chrono>
#include <cstdio>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <format>

#include "platform/win_util.h"

namespace argos::log {
namespace {

std::mutex g_mutex;
std::ofstream g_file;
std::deque<std::string> g_recent;
constexpr size_t kRecentCap = 500;

const char* level_name(Level level) {
    switch (level) {
        case Level::Debug: return "DEBUG";
        case Level::Info: return "INFO ";
        case Level::Warn: return "WARN ";
        case Level::Error: return "ERROR";
    }
    return "?????";
}

std::string timestamp() {
    const auto now = std::chrono::system_clock::now();
    const std::time_t t = std::chrono::system_clock::to_time_t(now);
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                        now.time_since_epoch()) % 1000;
    std::tm tm{};
    localtime_s(&tm, &t);
    return std::format("{:04d}-{:02d}-{:02d} {:02d}:{:02d}:{:02d}.{:03d}", tm.tm_year + 1900,
                       tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec, ms.count());
}

std::filesystem::path log_path() {
    static const std::filesystem::path p = [] {
        const auto dir = win::app_data_dir() / L"logs";
        std::error_code ec;
        std::filesystem::create_directories(dir, ec);

        const auto now = std::chrono::system_clock::now();
        const std::time_t t = std::chrono::system_clock::to_time_t(now);
        std::tm tm{};
        localtime_s(&tm, &t);
        return dir / std::format("argos-{:04d}{:02d}{:02d}.log", tm.tm_year + 1900, tm.tm_mon + 1,
                                 tm.tm_mday);
    }();
    return p;
}

}  // namespace

void init() {
    std::lock_guard lock(g_mutex);
    if (!g_file.is_open()) {
        g_file.open(log_path(), std::ios::out | std::ios::app);
    }
}

void write(Level level, std::string_view message) {
    const std::string line = std::format("{} [{}] {}", timestamp(), level_name(level), message);

    std::lock_guard lock(g_mutex);
    if (!g_file.is_open()) {
        g_file.open(log_path(), std::ios::out | std::ios::app);
    }
    if (g_file.is_open()) {
        g_file << line << '\n';
        g_file.flush();
    }
    ::OutputDebugStringA((line + "\n").c_str());

    g_recent.push_back(line);
    while (g_recent.size() > kRecentCap) g_recent.pop_front();
}

std::deque<std::string> recent(size_t max_lines) {
    std::lock_guard lock(g_mutex);
    std::deque<std::string> out;
    const size_t count = std::min(max_lines, g_recent.size());
    for (size_t i = g_recent.size() - count; i < g_recent.size(); ++i) out.push_back(g_recent[i]);
    return out;
}

}  // namespace argos::log
