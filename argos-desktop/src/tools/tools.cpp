// Tool executor: OpenAI-format schemas + dispatch (bridge -> native fallback).
// See tools.h for the architecture notes.

#include "tools/tools.h"

#include <windows.h>
#include <tlhelp32.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdio>
#include <cwctype>
#include <filesystem>
#include <format>
#include <fstream>
#include <functional>
#include <map>
#include <mutex>
#include <optional>
#include <regex>
#include <set>
#include <sstream>
#include <unordered_map>
#include <vector>

#include "bridge/ide_bridge.h"
#include "core/app.h"
#include "core/config.h"
#include "core/log.h"
#include "platform/win_util.h"
#include "voice/voice.h"

namespace argos::tools {
namespace fs = std::filesystem;

namespace {

// ──────────────────────────────────────────────────────────────────────────
// Path resolution
// ──────────────────────────────────────────────────────────────────────────

fs::path resolve(const std::string& p) {
    fs::path path = fs::path(win::to_wide(p));
    if (path.is_absolute()) return path;
    const auto& ws = app().ide().endpoint().workspace;
    if (!ws.empty()) return fs::path(win::to_wide(ws)) / path;
    return fs::current_path() / path;
}

std::string narrow(const fs::path& p) { return win::to_utf8(p.wstring()); }

Result fail(std::string msg) { return {false, std::move(msg), {}}; }
Result ok_result(std::string out, nlohmann::json data = {}) {
    return {true, std::move(out), std::move(data)};
}

// ──────────────────────────────────────────────────────────────────────────
// Native: file.read (line-numbered like Kilo's read_file)
// ──────────────────────────────────────────────────────────────────────────

constexpr size_t kMaxReadBytes = 4 * 1024 * 1024;

Result read_file_native(const fs::path& path, int offset, int limit) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return fail("cannot open: " + narrow(path));
    std::ostringstream ss;
    ss << in.rdbuf();
    std::string text = ss.str();
    if (text.size() > kMaxReadBytes) text.resize(kMaxReadBytes);

    std::vector<std::string> lines;
    std::string cur;
    std::istringstream ls(text);
    while (std::getline(ls, cur)) {
        if (!cur.empty() && cur.back() == '\r') cur.pop_back();
        lines.push_back(std::move(cur));
    }

    const int start = std::max(0, offset);
    const int end = limit > 0 ? std::min<int>(start + limit, (int)lines.size()) : (int)lines.size();
    std::ostringstream numbered;
    for (int i = start; i < end; ++i) numbered << (i + 1) << " | " << lines[i] << '\n';

    nlohmann::json data{{"path", narrow(path)}, {"lineCount", lines.size()},
                        {"offset", start},           {"text", text}};
    return ok_result(numbered.str(), std::move(data));
}

// ──────────────────────────────────────────────────────────────────────────
// Native: SEARCH/REPLACE block engine (port of extension-ide/edits.ts)
// ──────────────────────────────────────────────────────────────────────────

struct Block {
    int index = 0;
    int start_line = -1;  // 1-based hint, -1 = absent
    std::string search;
    std::string replace;
};

std::vector<std::string> split_lines(const std::string& s) {
    std::vector<std::string> out;
    std::istringstream in(s);
    std::string line;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        out.push_back(std::move(line));
    }
    return out;
}

std::vector<Block> parse_blocks(const std::string& diff) {
    const auto lines = split_lines(diff);
    std::vector<Block> blocks;
    size_t i = 0;
    const std::regex re_search(R"(^<{7}\s*SEARCH)");
    const std::regex re_hint(R"(^\s*:?start_line:?\s*(\d+))");
    const std::regex re_dash(R"(^-{7})");
    const std::regex re_eq(R"(^={7})");
    const std::regex re_end(R"(^>{7}\s*REPLACE)");
    std::smatch m;
    while (i < lines.size()) {
        if (!std::regex_search(lines[i], re_search)) {
            ++i;
            continue;
        }
        ++i;
        Block b;
        b.index = (int)blocks.size() + 1;
        if (i < lines.size() && std::regex_search(lines[i], m, re_hint)) {
            b.start_line = std::stoi(m[1].str());
            ++i;
        }
        if (i < lines.size() && std::regex_search(lines[i], re_dash)) ++i;
        std::ostringstream search;
        while (i < lines.size() && !std::regex_search(lines[i], re_eq))
            search << lines[i++] << '\n';
        if (i < lines.size()) ++i;
        std::ostringstream replace;
        while (i < lines.size() && !std::regex_search(lines[i], re_end))
            replace << lines[i++] << '\n';
        if (i < lines.size()) ++i;
        std::string s = search.str(), r = replace.str();
        if (!s.empty() && s.back() == '\n') s.pop_back();
        if (!r.empty() && r.back() == '\n') r.pop_back();
        b.search = std::move(s);
        b.replace = std::move(r);
        blocks.push_back(std::move(b));
    }
    return blocks;
}

std::string normalize_line(std::string line) {
    // trim + collapse internal whitespace
    const auto first = line.find_first_not_of(" \t");
    if (first == std::string::npos) return {};
    const auto last = line.find_last_not_of(" \t");
    line = line.substr(first, last - first + 1);
    std::string out;
    bool ws = false;
    for (char c : line) {
        if (c == ' ' || c == '\t') {
            if (!ws) out.push_back(' ');
            ws = true;
        } else {
            out.push_back(c);
            ws = false;
        }
    }
    return out;
}

// Fuzzy line-window match. Returns {start,end} (exclusive) or nullopt;
// ambiguous=true when two windows tie at the best score.
std::optional<std::pair<int, int>> fuzzy_find(const std::vector<std::string>& file,
                                              const std::string& search, int hint,
                                              bool& ambiguous) {
    ambiguous = false;
    const auto needle = split_lines(search);
    if (needle.empty() || needle.size() > file.size()) return std::nullopt;
    std::vector<std::string> norm_needle, norm_file;
    norm_needle.reserve(needle.size());
    norm_file.reserve(file.size());
    for (auto& l : needle) norm_needle.push_back(normalize_line(l));
    for (auto& l : file) norm_file.push_back(normalize_line(l));

    const int span = (int)file.size() - (int)needle.size();
    std::vector<int> order;
    if (hint > 0) {
        int center = std::clamp(hint - 1, 0, span);
        order.push_back(center);
        for (int d = 1; d <= span; ++d) {
            if (center - d >= 0) order.push_back(center - d);
            if (center + d <= span) order.push_back(center + d);
        }
    } else {
        for (int i = 0; i <= span; ++i) order.push_back(i);
    }

    int best = -1, ties = 0;
    double best_score = 0.0;
    for (int start : order) {
        int equal = 0;
        for (size_t j = 0; j < needle.size(); ++j)
            if (norm_file[start + j] == norm_needle[j]) ++equal;
        const double score = (double)equal / (double)needle.size();
        if (score > best_score) {
            best_score = score;
            best = start;
            ties = 1;
        } else if (score == best_score) {
            ++ties;
        }
        if (best_score == 1.0 && ties == 1) break;
    }
    if (best_score < 0.9 || best < 0) return std::nullopt;
    if (ties > 1) {
        ambiguous = true;
        return std::nullopt;
    }
    return std::pair{best, best + (int)needle.size()};
}

Result apply_diff_native(const fs::path& path, const std::string& diff) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return fail("cannot open: " + narrow(path));
    std::ostringstream ss;
    ss << in.rdbuf();
    in.close();
    std::string content = ss.str();

    const auto blocks = parse_blocks(diff);
    if (blocks.empty()) return fail("no SEARCH/REPLACE blocks in diff");

    int applied = 0;
    nlohmann::json failures = nlohmann::json::array();
    for (const auto& b : blocks) {
        if (b.search.empty()) {
            failures.push_back({{"block", b.index}, {"reason", "empty SEARCH"}});
            continue;
        }
        // exact match(es)
        std::vector<size_t> hits;
        for (size_t at = content.find(b.search); at != std::string::npos;
             at = content.find(b.search, at + 1))
            hits.push_back(at);
        if (hits.size() == 1 || (hits.size() > 1 && b.start_line > 0)) {
            size_t pick = hits[0];
            if (hits.size() > 1) {
                // nearest to the :start_line: hint
                auto line_of = [&](size_t off) {
                    return (int)std::count(content.begin(), content.begin() + off, '\n') + 1;
                };
                std::sort(hits.begin(), hits.end(), [&](size_t a, size_t c) {
                    return std::abs(line_of(a) - b.start_line) <
                           std::abs(line_of(c) - b.start_line);
                });
                pick = hits[0];
            }
            content.replace(pick, b.search.size(), b.replace);
            ++applied;
            continue;
        }
        // fuzzy
        auto lines = split_lines(content);
        bool ambiguous = false;
        auto m = fuzzy_find(lines, b.search, b.start_line, ambiguous);
        if (!m) {
            failures.push_back({{"block", b.index},
                                {"reason", ambiguous ? "SEARCH matched multiple locations"
                                                     : "SEARCH content not found"}});
            continue;
        }
        std::vector<std::string> merged;
        merged.insert(merged.end(), lines.begin(), lines.begin() + m->first);
        for (auto& l : split_lines(b.replace)) merged.push_back(std::move(l));
        merged.insert(merged.end(), lines.begin() + m->second, lines.end());
        std::ostringstream rebuilt;
        const char* eol = content.find("\r\n") != std::string::npos ? "\r\n" : "\n";
        for (size_t i = 0; i < merged.size(); ++i) {
            if (i) rebuilt << eol;
            rebuilt << merged[i];
        }
        content = rebuilt.str();
        ++applied;
    }

    if (applied > 0) {
        std::ofstream out(path, std::ios::binary | std::ios::trunc);
        out << content;
    }
    nlohmann::json data{{"blocks", applied}, {"failures", failures}};
    std::string summary = "applied " + std::to_string(applied) + "/" +
                          std::to_string(blocks.size()) + " blocks to " + narrow(path);
    return ok_result(std::move(summary), std::move(data));
}

// ──────────────────────────────────────────────────────────────────────────
// Native: regex content search (search_files)
// ──────────────────────────────────────────────────────────────────────────

const std::unordered_map<std::string, bool> kSkipDirs = {
    {".git", true},  {"node_modules", true}, {"dist", true},     {"out", true},
    {"build", true}, {".next", true},        {".cache", true},   {"target", true},
    {"__pycache__", true},                   {".venv", true},    {"venv", true},
};

std::regex glob_regex(const std::string& glob) {
    std::string re = glob;
    // escape regex specials, then translate wildcards
    std::string esc;
    for (char c : re) {
        if (std::string(".+^${}()|[]\\").find(c) != std::string::npos) esc.push_back('\\');
        esc.push_back(c);
    }
    std::string out;
    for (size_t i = 0; i < esc.size(); ++i) {
        if (esc[i] == '*' && i + 1 < esc.size() && esc[i + 1] == '*') {
            out += ".*";
            ++i;
        } else if (esc[i] == '*') {
            out += "[^/\\\\]*";
        } else if (esc[i] == '?') {
            out += "[^/\\\\]";
        } else {
            out.push_back(esc[i]);
        }
    }
    return std::regex(out, std::regex::icase);
}

Result search_files_native(const fs::path& root, const std::string& pattern,
                           const std::string& glob, int max_results) {
    if (!fs::exists(root)) return fail("no such directory: " + narrow(root));
    std::regex re;
    try {
        re = std::regex(pattern);
    } catch (const std::regex_error& e) {
        return fail(std::string("bad regex: ") + e.what());
    }
    std::optional<std::regex> glob_re;
    if (!glob.empty()) glob_re = glob_regex(glob);

    nlohmann::json matches = nlohmann::json::array();
    std::vector<fs::path> stack{root};
    std::error_code ec;
    while (!stack.empty() && (int)matches.size() < max_results) {
        const fs::path dir = stack.back();
        stack.pop_back();
        for (fs::directory_iterator it(dir, fs::directory_options::skip_permission_denied, ec),
             end;
             !ec && it != end; it.increment(ec)) {
            if ((int)matches.size() >= max_results) break;
            const auto p = it->path();
            if (it->is_directory(ec)) {
                if (!kSkipDirs.count(p.filename().string())) stack.push_back(p);
                continue;
            }
            const std::string full = narrow(p);
            if (glob_re && !std::regex_search(full, *glob_re)) continue;
            std::ifstream in(p, std::ios::binary);
            if (!in) continue;
            std::array<char, 512> head{};
            in.read(head.data(), head.size());
            if (std::find(head.begin(), head.begin() + in.gcount(), '\0') !=
                head.begin() + in.gcount())
                continue;  // binary
            std::string line;
            int lineno = 0;
            while ((int)matches.size() < max_results && std::getline(in, line)) {
                ++lineno;
                if (!line.empty() && line.back() == '\r') line.pop_back();
                if (std::regex_search(line, re)) {
                    matches.push_back({{"file", full},
                                       {"line", lineno},
                                       {"text", line.substr(0, 500)}});
                }
            }
        }
    }
    std::string summary = std::to_string(matches.size()) + " match(es) under " + narrow(root);
    return ok_result(summary + "\n" + matches.dump(2),
                     {{"count", matches.size()}, {"matches", matches}});
}

// ──────────────────────────────────────────────────────────────────────────
// Native: execute_command via cmd.exe with captured output
// ──────────────────────────────────────────────────────────────────────────

Result execute_command_native(const std::string& command, const std::string& cwd,
                              int timeout_ms) {
    SECURITY_ATTRIBUTES sa{};
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = TRUE;
    HANDLE read_pipe = nullptr, write_pipe = nullptr;
    if (!::CreatePipe(&read_pipe, &write_pipe, &sa, 0)) return fail("CreatePipe failed");
    ::SetHandleInformation(read_pipe, HANDLE_FLAG_INHERIT, 0);

    STARTUPINFOW si{};
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;
    si.hStdOutput = write_pipe;
    si.hStdError = write_pipe;
    si.hStdInput = ::GetStdHandle(STD_INPUT_HANDLE);

    // Restrict inheritance to just the stdio handles — bInheritHandles=TRUE
    // alone would hand the child every inheritable handle argos holds (incl.
    // the command server's listen socket); a child that outlives argos then
    // zombie-binds the port.
    HANDLE inherit[2] = {write_pipe, nullptr};
    DWORD inherit_count = 1;
    if (si.hStdInput && si.hStdInput != INVALID_HANDLE_VALUE)
        inherit[inherit_count++] = si.hStdInput;
    SIZE_T attr_bytes = 0;
    ::InitializeProcThreadAttributeList(nullptr, 1, 0, &attr_bytes);
    auto* attr_list = static_cast<PPROC_THREAD_ATTRIBUTE_LIST>(
        ::HeapAlloc(::GetProcessHeap(), 0, attr_bytes));
    bool attrs_ok = attr_list &&
                    ::InitializeProcThreadAttributeList(attr_list, 1, 0,
                                                        &attr_bytes) &&
                    ::UpdateProcThreadAttribute(
                        attr_list, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                        inherit, inherit_count * sizeof(HANDLE), nullptr,
                        nullptr);

    STARTUPINFOEXW six{};
    six.StartupInfo = si;
    six.StartupInfo.cb = sizeof(six);  // must be the EX size, not STARTUPINFOW
    six.lpAttributeList = attrs_ok ? attr_list : nullptr;

    std::wstring cmdline = L"cmd.exe /c " + win::to_wide(command);
    std::wstring workdir = cwd.empty() ? std::wstring() : fs::path(win::to_wide(cwd)).wstring();

    PROCESS_INFORMATION pi{};
    const BOOL launched = ::CreateProcessW(
        nullptr, cmdline.data(), nullptr, nullptr, TRUE,
        CREATE_NO_WINDOW | (attrs_ok ? EXTENDED_STARTUPINFO_PRESENT : 0),
        nullptr, workdir.empty() ? nullptr : workdir.c_str(),
        &six.StartupInfo, &pi);
    if (attr_list) {
        ::DeleteProcThreadAttributeList(attr_list);
        ::HeapFree(::GetProcessHeap(), 0, attr_list);
    }
    ::CloseHandle(write_pipe);
    if (!launched) {
        ::CloseHandle(read_pipe);
        return fail("CreateProcess failed: " + std::to_string(::GetLastError()));
    }

    std::string output;
    std::array<char, 8192> buf{};
    const DWORD deadline = ::GetTickCount() + (DWORD)timeout_ms;
    bool timed_out = false;
    for (;;) {
        DWORD avail = 0;
        ::PeekNamedPipe(read_pipe, nullptr, 0, nullptr, &avail, nullptr);
        while (avail > 0) {
            DWORD read = 0;
            const DWORD want = std::min<DWORD>(avail, (DWORD)buf.size());
            if (!::ReadFile(read_pipe, buf.data(), want, &read, nullptr) || read == 0) break;
            output.append(buf.data(), read);
            if (output.size() > 256 * 1024) {  // cap captured output
                output += "\n... (output truncated)";
                goto drain_done;
            }
            ::PeekNamedPipe(read_pipe, nullptr, 0, nullptr, &avail, nullptr);
        }
        const DWORD wait = ::WaitForSingleObject(pi.hProcess, 200);
        if (wait == WAIT_OBJECT_0) break;
        if ((int)(::GetTickCount() - deadline) >= 0) {
            timed_out = true;
            ::TerminateProcess(pi.hProcess, 1);
            break;
        }
    }
drain_done:
    // drain whatever is left
    for (;;) {
        DWORD avail = 0;
        if (!::PeekNamedPipe(read_pipe, nullptr, 0, nullptr, &avail, nullptr) || avail == 0) break;
        DWORD read = 0;
        if (!::ReadFile(read_pipe, buf.data(), std::min<DWORD>(avail, (DWORD)buf.size()), &read,
                        nullptr) ||
            read == 0)
            break;
        output.append(buf.data(), read);
    }
    DWORD exit_code = 0;
    ::GetExitCodeProcess(pi.hProcess, &exit_code);
    ::CloseHandle(pi.hProcess);
    ::CloseHandle(pi.hThread);
    ::CloseHandle(read_pipe);

    nlohmann::json data{{"exitCode", exit_code}, {"timedOut", timed_out}};
    std::string head = timed_out ? "command timed out\n" : "";
    return {exit_code == 0 && !timed_out, head + output, std::move(data)};
}

// ──────────────────────────────────────────────────────────────────────────
// Native file ops
// ──────────────────────────────────────────────────────────────────────────

Result write_file_native(const fs::path& path, const std::string& content) {
    std::error_code ec;
    fs::create_directories(path.parent_path(), ec);
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) return fail("cannot write: " + narrow(path));
    out << content;
    return ok_result("wrote " + std::to_string(content.size()) + " bytes to " + narrow(path));
}

Result list_files_native(const fs::path& dir) {
    std::error_code ec;
    if (!fs::is_directory(dir, ec)) return fail("no such directory: " + narrow(dir));
    nlohmann::json entries = nlohmann::json::array();
    for (fs::directory_iterator it(dir, ec), end; !ec && it != end; it.increment(ec)) {
        entries.push_back({{"name", narrow(it->path().filename())},
                           {"kind", it->is_directory(ec) ? "dir" : "file"}});
    }
    std::ostringstream lines;
    for (auto& e : entries) lines << (e["kind"] == "dir" ? "[dir]  " : "       ") << e["name"].get<std::string>() << '\n';
    return ok_result(lines.str(), {{"path", narrow(dir)}, {"entries", entries}});
}

Result insert_content_native(const fs::path& path, int line, const std::string& text) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return fail("cannot open: " + narrow(path));
    std::ostringstream ss;
    ss << in.rdbuf();
    in.close();
    auto lines = split_lines(ss.str());
    const int at = std::clamp(line, 0, (int)lines.size());
    std::ostringstream rebuilt;
    for (int i = 0; i < (int)lines.size(); ++i) {
        if (i == at) rebuilt << text;
        rebuilt << lines[i] << '\n';
    }
    if (at == (int)lines.size()) rebuilt << text;
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    out << rebuilt.str();
    return ok_result("inserted at line " + std::to_string(at + 1) + " in " + narrow(path));
}

Result file_stat_native(const fs::path& path) {
    std::error_code ec;
    if (!fs::exists(path, ec)) return ok_result("not found: " + narrow(path), {{"exists", false}});
    const auto st = fs::status(path, ec);
    nlohmann::json data{{"exists", true},
                        {"path", narrow(path)},
                        {"kind", fs::is_directory(st) ? "dir" : "file"}};
    if (!fs::is_directory(st)) data["size"] = fs::file_size(path, ec);
    // Read `kind` before std::move(data) — argument evaluation order is
    // unspecified, so the move could run first and leave `data` null.
    const std::string kind = data["kind"].get<std::string>();
    return ok_result(narrow(path) + " (" + kind + ")", std::move(data));
}

Result rename_native(const fs::path& from, const fs::path& to, bool overwrite) {
    std::error_code ec;
    if (fs::exists(to, ec) && !overwrite) return fail("target exists: " + narrow(to));
    fs::create_directories(to.parent_path(), ec);
    fs::rename(from, to, ec);
    if (ec) return fail("rename failed: " + ec.message());
    return ok_result(narrow(from) + " -> " + narrow(to));
}

Result copy_native(const fs::path& from, const fs::path& to, bool overwrite) {
    std::error_code ec;
    fs::create_directories(to.parent_path(), ec);
    fs::copy(from, to,
             fs::copy_options::recursive |
                 (overwrite ? fs::copy_options::overwrite_existing : fs::copy_options::none),
             ec);
    if (ec) return fail("copy failed: " + ec.message());
    return ok_result(narrow(from) + " -> " + narrow(to));
}

// ──────────────────────────────────────────────────────────────────────────
// Bridge dispatch
// ──────────────────────────────────────────────────────────────────────────

const std::unordered_map<std::string, std::string> kBridgeMethods = {
    {"read_file", "file.read"},         {"write_file", "file.write"},
    {"apply_diff", "file.apply_diff"},  {"insert_content", "file.insert"},
    {"list_files", "file.list"},        {"file_stat", "file.stat"},
    {"search_files", "text.search"},    {"rename_file", "file.rename"},
    {"copy_file", "file.copy"},         {"make_dir", "file.mkdir"},
    {"delete_file", "file.delete"},     {"execute_command", "terminal.run"},
    {"open_in_editor", "file.open"},    {"get_diagnostics", "problems.get"},
    {"list_symbols", "symbols.list"},   {"ide_status", "ide.status"},
    {"save_file", "editor.save"},       {"save_all", "editor.saveAll"},
    {"ide_chat", "chat.send"},          {"ide_chat_new", "chat.new"},
};

// Terminal windows (Windows Terminal, classic conhost) — AI coder CLIs live
// here. They can't register a bridge, so dispatch pastes into them instead.
struct TermWin {
    DWORD pid;
    std::string title;
    std::string cls;
    HWND hwnd;
};

std::vector<TermWin> find_terminals() {
    std::vector<TermWin> out;
    ::EnumWindows(
        [](HWND hwnd, LPARAM lp) -> BOOL {
            auto& v = *reinterpret_cast<std::vector<TermWin>*>(lp);
            if (!::IsWindowVisible(hwnd)) return TRUE;
            const auto cls = win::window_class_name(hwnd);
            if (cls != L"CASCADIA_HOSTING_WINDOW_CLASS" &&
                cls != L"ConsoleWindowClass")
                return TRUE;
            DWORD pid = 0;
            ::GetWindowThreadProcessId(hwnd, &pid);
            if (pid == 0 || pid == ::GetCurrentProcessId()) return TRUE;
            const auto title = win::window_text(hwnd);
            if (title.empty()) return TRUE;
            v.push_back({pid, win::to_utf8(title), win::to_utf8(cls), hwnd});
            return TRUE;
        },
        reinterpret_cast<LPARAM>(&out));
    return out;
}

// ──────────────────────────────────────────────────────────────────────────
// CLI agent detection — AI coding CLIs installed via npm (IBM Bob CLI,
// Gemini CLI, Claude Code, Codex CLI, …) and which of them are RUNNING right
// now. Running detection reads process command lines (NtQueryInformationProcess
// class 60) and matches a per-CLI path fragment; installed detection just
// checks the npm shim (%APPDATA%\npm\<bin>.cmd). Cached ~6s.
// ──────────────────────────────────────────────────────────────────────────

struct CliSpec {
    const char* name;
    const char* bin;       // npm shim name (%APPDATA%\npm\<bin>.cmd)
    const char* fragment;  // lowercase cmdline fragment (path-normalized)
    bool headline;         // show in the fleet strip when installed-not-running
};

const CliSpec kCliSpecs[] = {
    {"IBM Bob CLI", "bob", "bobshell", true},
    {"Gemini CLI", "gemini", "@google\\gemini-cli", true},
    {"Claude Code", "claude", "@anthropic-ai\\claude-code", true},
    {"Codex CLI", "codex", "@openai\\codex", true},
    {"Copilot CLI", "copilot", "@github\\copilot", false},
    {"Kilo Code CLI", "kilocode", "@kilocode\\cli", false},
    {"opencode", "opencode", "node_modules\\opencode", false},
    {"Cline CLI", "cline", "node_modules\\cline", false},
    {"Clawdbot", "clawdbot", "clawdbot", false},
    {"Bonsai", "bonsai", "bonsai", false},
};

// Full command line of a process (works for same-user processes without
// admin). Returns lowercase, '/'-normalized to '\' for stable matching.
std::string process_cmdline_lower(DWORD pid) {
    using NtQIP = LONG(NTAPI*)(HANDLE, ULONG, PVOID, ULONG, PULONG);
    static NtQIP nt = reinterpret_cast<NtQIP>(
        ::GetProcAddress(::GetModuleHandleW(L"ntdll.dll"),
                         "NtQueryInformationProcess"));
    if (!nt) return {};
    HANDLE h = ::OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!h) return {};
    struct US {
        USHORT Length;
        USHORT MaximumLength;
        PWSTR Buffer;
    };
    ULONG len = 0;
    std::string out;
    // The sizing probe returns STATUS_INFO_LENGTH_MISMATCH (0xC0000004) with
    // the required length — not 0 — so only the length matters here.
    nt(h, 60 /*ProcessCommandLineInformation*/, nullptr, 0, &len);
    if (len > sizeof(US)) {
        std::vector<BYTE> buf(len);
        ULONG got = 0;
        if (nt(h, 60, buf.data(), len, &got) == 0) {
            auto* us = reinterpret_cast<US*>(buf.data());
            if (us->Buffer && us->Length)
                out = win::to_utf8(std::wstring(us->Buffer, us->Length / 2));
        }
    }
    ::CloseHandle(h);
    std::transform(out.begin(), out.end(), out.begin(),
                   [](unsigned char c) { return (char)std::tolower(c); });
    std::replace(out.begin(), out.end(), '/', '\\');
    return out;
}

struct CliAgentState {
    std::string name;
    std::string bin;
    DWORD pid = 0;
    bool running = false;
    bool installed = false;
    bool headline = false;
};

std::mutex g_cli_mu;
std::vector<CliAgentState> g_cli_state;
unsigned long long g_cli_at = 0;

void refresh_cli_agents() {
    std::map<std::string, DWORD> running;  // fragment → pid
    HANDLE snap = ::CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap != INVALID_HANDLE_VALUE) {
        PROCESSENTRY32W pe{};
        pe.dwSize = sizeof(pe);
        if (::Process32FirstW(snap, &pe)) {
            do {
                std::wstring exe = pe.szExeFile;
                std::transform(exe.begin(), exe.end(), exe.begin(),
                               [](wchar_t c) { return (wchar_t)::towlower(c); });
                const bool candidate =
                    exe == L"node.exe" || exe == L"bun.exe" ||
                    exe == L"deno.exe" || exe == L"bob.exe" ||
                    exe == L"python.exe" || exe == L"python3.exe";
                if (!candidate) continue;
                const auto cmd = process_cmdline_lower(pe.th32ProcessID);
                if (cmd.empty()) continue;
                for (const auto& spec : kCliSpecs)
                    if (cmd.find(spec.fragment) != std::string::npos &&
                        !running.count(spec.fragment))
                        running[spec.fragment] = pe.th32ProcessID;
            } while (::Process32NextW(snap, &pe));
        }
        ::CloseHandle(snap);
    }

    const auto npm_dir = win::app_data_dir().parent_path() / L"npm";
    std::vector<CliAgentState> next;
    for (const auto& spec : kCliSpecs) {
        CliAgentState s;
        s.name = spec.name;
        s.bin = spec.bin;
        s.headline = spec.headline;
        std::error_code ec;
        s.installed = std::filesystem::exists(
            npm_dir / (std::string(spec.bin) + ".cmd"), ec);
        const auto it = running.find(spec.fragment);
        if (it != running.end()) {
            s.running = true;
            s.pid = it->second;
        }
        if (s.running || s.installed) next.push_back(std::move(s));
    }
    std::lock_guard lock(g_cli_mu);
    g_cli_state = std::move(next);
    g_cli_at = ::GetTickCount64();
}

std::vector<CliAgentState> cli_agents() {
    if (::GetTickCount64() - g_cli_at > 6000) refresh_cli_agents();
    std::lock_guard lock(g_cli_mu);
    return g_cli_state;
}

// Resolve a target selector to live bridge endpoints. Selector: empty/"current"
// → the bound IDE, "all" → the whole fleet, a number → pid/main_pid, otherwise
// a case-insensitive substring of the IDE name ("bob", "code", "windsurf").
std::vector<bridge::BridgeEndpoint> resolve_ides(const nlohmann::json& args) {
    const std::string sel = args.value("target", args.value("ide", ""));
    auto all = bridge::IdeBridge::enumerate();
    if (sel.empty() || sel == "current") {
        if (app().ide().connected()) return {app().ide().endpoint()};
        return {};
    }
    if (sel == "all") return all;
    const bool numeric =
        !sel.empty() &&
        std::all_of(sel.begin(), sel.end(),
                    [](unsigned char c) { return std::isdigit(c); });
    const int want_pid = numeric ? std::stoi(sel) : 0;
    std::string lower = sel;
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c) { return std::tolower(c); });
    std::vector<bridge::BridgeEndpoint> out;
    for (const auto& ep : all) {
        if (want_pid) {
            if (ep.pid == want_pid || ep.main_pid == want_pid) out.push_back(ep);
            continue;
        }
        std::string name = ep.ide;
        std::transform(name.begin(), name.end(), name.begin(),
                       [](unsigned char c) { return std::tolower(c); });
        if (name.find(lower) != std::string::npos) out.push_back(ep);
    }
    return out;
}

// Finish a needs-paste delivery on a specific endpoint: raise that IDE's
// window (not just the bound one) and send the synthetic Ctrl+V / Enter.
void complete_paste_on(const bridge::BridgeEndpoint& ep, nlohmann::json& res) {
    const DWORD pid = (DWORD)(ep.main_pid ? ep.main_pid : ep.pid);
    if (win::bring_process_to_front(pid)) {
        // Chat webviews (Bob's especially) take a beat to accept focus — too
        // short and the paste lands in the IDE's terminal instead.
        ::Sleep(600);
        // Re-assert the chat input focus after the window switch (also clears
        // any menu/quick-input focus the switch disturbed). Commands that
        // don't exist in this IDE error out harmlessly.
        bridge::IdeBridge::call_on(ep, "ide.command",
                                   {{"command", "workbench.action.chat.focusInput"}});
        ::Sleep(500);
        win::send_paste_enter();
        res["delivered"] = "pasted";
    } else {
        res["detail"] = "IDE window did not take focus — prompt left on clipboard";
    }
}

Result via_bridge(const std::string& tool, const nlohmann::json& args) {
    const auto it = kBridgeMethods.find(tool);
    if (it == kBridgeMethods.end()) return fail("unknown tool: " + tool);

    // Explicit target → per-endpoint call; without one the bound IDE handles it.
    const std::string sel = args.value("target", args.value("ide", ""));
    nlohmann::json fwd = args;
    fwd.erase("target");
    fwd.erase("ide");
    if (!sel.empty() && sel != "current") {
        auto eps = resolve_ides(args);
        if (eps.empty()) return fail("no live IDE matches target '" + sel + "'");
        nlohmann::json results = nlohmann::json::array();
        for (auto& ep : eps) {
            auto r = bridge::IdeBridge::call_on(ep, it->second, fwd);
            if (!r) {
                results.push_back({{"ide", ep.ide}, {"port", ep.port},
                                   {"ok", false}});
                continue;
            }
            if (it->second == "chat.send" &&
                r->value("delivered", "") == "needs-paste")
                complete_paste_on(ep, *r);
            r->push_back({"_ide", ep.ide});
            r->push_back({"_port", ep.port});
            results.push_back(*r);
        }
        return results.size() == 1
                   ? ok_result(results[0].dump(2), results[0])
                   : ok_result(nlohmann::json{{"targets", results}}.dump(2),
                               {{"targets", results}});
    }

    if (app().ide().connected()) {
        auto result = app().ide().call(it->second, fwd);
        if (!result) return fail("bridge: " + app().ide().last_error());
        // Webview chats can't be written programmatically — the extension
        // focuses the input and stages the prompt on the clipboard, so finish
        // the delivery here with a synthetic Ctrl+V / Enter into the window.
        if (it->second == "chat.send" &&
            result->value("delivered", "") == "needs-paste") {
            complete_paste_on(app().ide().endpoint(), *result);
        }
        return ok_result(result->dump(2), *result);
    }
    // No bound endpoint — fall back to the first live registration so bridge
    // tools keep working whenever ANY IDE is up (bound or not).
    auto eps = bridge::IdeBridge::enumerate();
    if (eps.empty()) return fail("no IDE bridge connected");
    auto& ep = eps.front();
    auto r = bridge::IdeBridge::call_on(ep, it->second, fwd);
    if (!r) return fail("bridge call failed on " + ep.ide);
    if (it->second == "chat.send" &&
        r->value("delivered", "") == "needs-paste")
        complete_paste_on(ep, *r);
    r->push_back({"_ide", ep.ide});
    return ok_result(r->dump(2), *r);
}

// ──────────────────────────────────────────────────────────────────────────
// ide_dispatch — fan out a plan of independent tasks across every connected
// IDE's AI assistant. Each entry picks a target IDE (name substring, pid, or
// "all") plus the prompt to hand its chat. Serialised so paste-deliveries
// don't fight over window focus.
// ──────────────────────────────────────────────────────────────────────────

// ──────────────────────────────────────────────────────────────────────────
// Terminal task queue — %APPDATA%\ArgosDesktop\terminal-tasks.json. When a
// task is pasted into a terminal we drop a claimable record here so an
// MCP-bridged CLI agent (Gemini CLI, Claude Code, Codex, …) can pull the real
// instructions with argos_get_task and report back with argos_send_reply.
// ──────────────────────────────────────────────────────────────────────────

std::filesystem::path terminal_tasks_path() {
    return win::app_data_dir() / L"terminal-tasks.json";
}

nlohmann::json terminal_tasks_read() {
    std::ifstream in(terminal_tasks_path());
    if (!in) return nlohmann::json{{"tasks", nlohmann::json::array()}};
    try {
        return nlohmann::json::parse(in);
    } catch (...) {
        return nlohmann::json{{"tasks", nlohmann::json::array()}};
    }
}

void terminal_tasks_write(const nlohmann::json& doc) {
    std::ofstream out(terminal_tasks_path(), std::ios::trunc);
    out << doc.dump(2);
}

std::string terminal_task_enqueue(DWORD pid, const std::string& title,
                                  const std::string& text) {
    auto doc = terminal_tasks_read();
    const auto id =
        "t-" + std::to_string((long long)::GetTickCount64()) + "-" +
        std::to_string(pid);
    doc["tasks"].push_back({{"id", id},
                            {"pid", (long long)pid},
                            {"title", title},
                            {"text", text},
                            {"status", "pending"},
                            {"ts", (long long)::time(nullptr)}});
    // Keep the file bounded — drop completed records past 200.
    auto& tasks = doc["tasks"];
    if (tasks.size() > 200) {
        nlohmann::json keep = nlohmann::json::array();
        for (auto& t : tasks)
            if (t.value("status", "") != "done") keep.push_back(t);
        while (keep.size() > 200) keep.erase(keep.begin());
        doc["tasks"] = keep;
    }
    terminal_tasks_write(doc);
    return id;
}

Result ide_dispatch(const nlohmann::json& a) {
    if (!a.contains("tasks") || !a["tasks"].is_array() || a["tasks"].empty())
        return fail("ide_dispatch needs a non-empty tasks[] array");
    const auto all = bridge::IdeBridge::enumerate();
    const auto terms = find_terminals();
    if (all.empty() && terms.empty())
        return fail("no IDE bridges or terminal windows found");

    nlohmann::json results = nlohmann::json::array();
    for (const auto& t : a["tasks"]) {
        const std::string text = t.value("text", t.value("task", ""));
        if (text.empty()) {
            results.push_back({{"ok", false}, {"error", "empty task text"}});
            continue;
        }
        const std::string sel = t.value("target", std::string("current"));
        std::string lower = sel;
        std::transform(lower.begin(), lower.end(), lower.begin(),
                       [](unsigned char c) { return std::tolower(c); });
        const bool want_terminals =
            lower == "all" || lower == "terminal" || lower == "terminals" ||
            lower == "wt" || lower == "cmd" || lower == "powershell";

        // Terminal targets: same delivery as a needs-paste IDE — raise the
        // window, stage the clipboard, send Ctrl+V + Enter. A singular
        // selector ("terminal", "wt", ...) takes the first match; only the
        // explicit plurals/"all" broadcast — Windows Terminal tabs share one
        // process, so per-window pastes would double-send into one console.
        // A selector that isn't an IDE name but matches a terminal window
        // title (e.g. "Ready" for the Gemini CLI window) also lands here.
        bool title_hit = false;
        for (const auto& tw : terms) {
            std::string tl = tw.title;
            std::transform(tl.begin(), tl.end(), tl.begin(),
                           [](unsigned char c) { return std::tolower(c); });
            if (tl.find(lower) != std::string::npos) {
                title_hit = true;
                break;
            }
        }
        if (want_terminals || lower.find("term") != std::string::npos ||
            title_hit) {
            const bool broadcast = lower == "all" || lower == "terminals";
            std::set<DWORD> delivered_pids;
            for (const auto& tw : terms) {
                if (!(lower == "all" || lower == "terminal" ||
                      lower == "terminals" || lower == "wt")) {
                    std::string tl = tw.title;
                    std::transform(tl.begin(), tl.end(), tl.begin(),
                                   [](unsigned char c) { return std::tolower(c); });
                    if (tl.find(lower) == std::string::npos) continue;
                }
                if (!delivered_pids.insert(tw.pid).second) continue;
                win::set_clipboard_text(win::to_wide(text));
                nlohmann::json row{{"terminal", tw.title},
                                   {"pid", tw.pid},
                                   {"task", text.substr(0, 60)}};
                if (win::bring_window_to_front(tw.hwnd)) {
                    ::Sleep(900);
                    win::send_paste_enter();
                    row["ok"] = true;
                    row["delivered"] = "pasted";
                    // Queue a claimable record so an MCP-bridged CLI agent in
                    // this terminal can pull the task back with argos_get_task.
                    row["task_id"] =
                        terminal_task_enqueue(tw.pid, tw.title, text);
                } else {
                    row["ok"] = false;
                    row["error"] = "terminal window did not take focus";
                }
                results.push_back(row);
                if (!broadcast) break;
            }
        }

        auto eps = resolve_ides(t);
        if (eps.empty() && !want_terminals && !title_hit) {
            results.push_back({{"ok", false},
                               {"error", "no IDE matches '" + sel + "'"},
                               {"task", text.substr(0, 60)}});
            continue;
        }
        for (auto& ep : eps) {
            nlohmann::json params{{"text", text}};
            if (t.contains("provider")) params["provider"] = t["provider"];
            params["submit"] = t.value("submit", true);
            auto r = bridge::IdeBridge::call_on(ep, "chat.send", params);
            nlohmann::json row{{"ide", ep.ide},
                               {"port", ep.port},
                               {"workspace", ep.workspace},
                               {"task", text.substr(0, 60)}};
            if (r) {
                row["ok"] = true;
                row["delivered"] = r->value("delivered", "unknown");
                row["provider"] = r->value("provider", "");
                if (row["delivered"] == "needs-paste") {
                    complete_paste_on(ep, *r);
                    row["delivered"] = r->value("delivered", "unknown");
                }
            } else {
                row["ok"] = false;
                row["error"] = "bridge call failed";
            }
            results.push_back(row);
        }
    }
    int sent = 0;
    std::string summary;
    for (const auto& row : results) {
        const bool ok = row.value("ok", false);
        if (ok) ++sent;
        const std::string who =
            row.value("ide", row.value("terminal", std::string("?")));
        summary += "• " + who + ": " +
                   (ok ? row.value("delivered", std::string("sent"))
                       : "failed — " + row.value("error", std::string("?"))) +
                   "\n";
    }
    return ok_result(std::format("dispatched {} task(s) across {} target(s)\n{}"
                                 "\nNext step (do this now, do not stop here): "
                                 "call wait_seconds{{seconds:20}} so the agents "
                                 "work, then ide_status{{}} and report each "
                                 "target's state to the user.",
                                 sent, results.size(), summary),
                     {{"results", results}});
}

// ──────────────────────────────────────────────────────────────────────────
// onboard_project — recon a repo, then hand the deep analysis to the IDE's
// AI assistant (IBM Bob) through the bridge chat. When no IDE is connected
// the mission is written to ONBOARDING_MISSION.md and staged on the
// clipboard so the user can paste it into Bob manually.
// ──────────────────────────────────────────────────────────────────────────

// Cheap repository recon: top-level entries, an extension census, and the
// first chunk of whatever README/manifest files exist. ~8 KB cap total.
std::string recon_repo(const fs::path& root) {
    std::error_code ec;
    std::ostringstream out;
    out << "repo root: " << narrow(root) << "\n\nTop level:\n";
    int n = 0;
    for (const auto& e : fs::directory_iterator(root, ec)) {
        out << (e.is_directory(ec) ? "  [dir]  " : "  [file] ")
            << e.path().filename().string() << "\n";
        if (++n >= 60) {
            out << "  …\n";
            break;
        }
    }

    // Shallow walk for an extension census — skip noise dirs, cap entries.
    std::map<std::string, int> exts;
    int seen = 0;
    std::function<void(const fs::path&, int)> walk = [&](const fs::path& d,
                                                         int depth) {
        if (depth > 3 || seen > 600) return;
        for (const auto& e : fs::directory_iterator(d, ec)) {
            if (++seen > 600) return;
            const auto name = e.path().filename().string();
            if (name == ".git" || name == "node_modules" || name == "build" ||
                name == "bin" || name == "obj" || name == "vcpkg_installed" ||
                name == "dist" || name == ".next" || name == "target")
                continue;
            if (e.is_directory(ec))
                walk(e.path(), depth + 1);
            else {
                auto ext = e.path().extension().string();
                exts[ext.empty() ? "(no ext)" : ext]++;
            }
        }
    };
    walk(root, 0);

    std::vector<std::pair<std::string, int>> sorted(exts.begin(), exts.end());
    std::sort(sorted.begin(), sorted.end(),
              [](auto& a, auto& b) { return a.second > b.second; });
    out << "\nFile types:";
    for (size_t i = 0; i < sorted.size() && i < 12; ++i)
        out << " " << sorted[i].first << "=" << sorted[i].second;
    out << "\n";

    const char* kManifests[] = {
        "README.md",  "README.txt",       "README",        "package.json",
        "pom.xml",    "build.gradle",     "build.gradle.kts", "CMakeLists.txt",
        "Cargo.toml", "go.mod",           "requirements.txt", "pyproject.toml",
        "pubspec.yaml", "composer.json",  "build.gradle",
    };
    for (const char* m : kManifests) {
        const fs::path f = root / m;
        if (!fs::is_regular_file(f, ec)) continue;
        std::ifstream in(f, std::ios::binary);
        std::string text(1800, '\0');
        in.read(text.data(), (std::streamsize)text.size());
        text.resize((size_t)in.gcount());
        out << "\n--- " << m << " (excerpt) ---\n" << text << "\n";
        if (out.tellp() > 8000) break;
    }

    // Recent history + top contributors — the "what's the team doing" and
    // "who do I ask" picture. Skipped silently when git isn't available.
    if (fs::is_directory(root / ".git", ec)) {
        auto git = [&](const char* cmd) {
            Result r = execute_command_native(cmd, narrow(root), 15000);
            return r.ok ? r.output : std::string();
        };
        const std::string log_out =
            git("git log -12 --pretty=format:\"%h %ad %an %s\" --date=short");
        if (!log_out.empty()) out << "\nRecent commits:\n" << log_out << "\n";
        const std::string who = git("git shortlog -sne HEAD");
        if (!who.empty()) {
            out << "\nTop contributors (who to ask):\n";
            std::istringstream wl(who);
            std::string line;
            for (int i = 0; i < 8 && std::getline(wl, line); ++i)
                if (!line.empty()) out << "  " << line << "\n";
        }
    }
    return out.str();
}

Result onboard_project(const nlohmann::json& a) {
    const std::string p = a.value("path", "");
    const fs::path root = p.empty() ? resolve("") : resolve(p);
    std::error_code ec;
    if (!fs::is_directory(root, ec))
        return fail("not a directory: " + narrow(root));

    const std::string recon = recon_repo(root);
    const std::string mission = std::format(
        R"(You are the onboarding engineer for this repository — a new developer must become productive today.

Argos (a desktop companion agent) did this quick recon:

{}

Your mission (use agent mode, subagents and document understanding as needed):
1. Analyze the codebase: purpose, tech stack, architecture, entry points, data flow, key modules.
2. Write AGENTS.md at the repo root — persistent project context for AI agents: build/test commands, conventions, key directories.
3. Write ONBOARDING.md at the repo root — start with a ```mermaid fenced block containing a mermaid "graph TD" architecture diagram of the project structure (top-level modules and how they connect), then: architecture map, module tour, exact setup/build/test commands, 3 suggested first tasks ranked by difficulty, an "unwritten conventions and gotchas" section (error-handling style, where validation lives, test patterns, naming, known traps), and a "who to ask" section mapping key modules to their top contributors.
4. If setup needs steps (dependency install, env vars, tooling), create a script (scripts/setup.ps1 or scripts/setup.sh) that performs them.
5. Append repo-specific items to ONBOARDING_CHECKLIST.md — Argos already wrote the standard items; keep its checkbox lines intact.
6. Finish with a 5-sentence spoken-style summary: what the project is, the stack, how to build and test it, and the recommended first task.)",
        recon);

    // Always leave the mission as a file — it's a reviewable artifact and the
    // manual fallback path.
    const fs::path mission_file = root / "ONBOARDING_MISSION.md";
    {
        std::ofstream f(mission_file, std::ios::binary | std::ios::trunc);
        f << mission;
    }
    log::info("onboard_project: mission written to " + narrow(mission_file));

    // Standard checklist — verify_setup ticks these boxes as milestones are
    // proven. Bob may append repo-specific items (per the mission prompt).
    const fs::path checklist_file = root / "ONBOARDING_CHECKLIST.md";
    if (!fs::exists(checklist_file, ec)) {
        std::ofstream f(checklist_file, std::ios::binary | std::ios::trunc);
        f << "# Onboarding checklist\n\n"
             "- [ ] Read ONBOARDING.md\n"
             "- [ ] Environment setup completed\n"
             "- [ ] Project builds\n"
             "- [ ] Tests pass\n"
             "- [ ] Guided codebase tour taken\n"
             "- [ ] First task picked\n"
             "- [ ] First commit merged\n";
    }

    nlohmann::json data = {{"repo", narrow(root)},
                           {"mission_file", narrow(mission_file)},
                           {"checklist_file", narrow(checklist_file)}};
    std::string status;

    // send_to_ide=false → preview mode: the recon stays in the Argos chatbox
    // and the mission only lives in ONBOARDING_MISSION.md (the robot's
    // right-click "Onboard this repo" uses this so nothing jumps to the IDE).
    const bool send_to_ide = a.value("send_to_ide", true);
    if (!send_to_ide) {
        status = "recon complete — structure shown here; the full onboarding "
                 "mission is saved to ONBOARDING_MISSION.md. Say \"send the "
                 "onboarding mission to the IDEs\" to hand it off.";
        data["delivered"] = "chatbox";
        data["status"] = status;
        return ok_result(status + "\n\nrecon:\n" + recon, data);
    }

    if (app().ide().refresh() && app().ide().connected()) {
        // Fresh session, then the mission. chat.new may no-op on generic
        // providers — that's fine.
        app().ide().call("chat.new", {});
        auto r = app().ide().call(
            "chat.send", {{"text", mission}, {"submit", true}});
        if (r) {
            const std::string delivered = r->value("delivered", "");
            const std::string provider = r->value("provider", "ide");
            if (delivered == "needs-paste") {
                if (win::bring_process_to_front(
                        (DWORD)app().ide().endpoint().main_pid)) {
                    ::Sleep(450);
                    win::send_paste_enter();
                    status = "mission pasted into the " + provider + " chat";
                    data["delivered"] = "pasted";
                } else {
                    status = "IDE window would not take focus — mission is on "
                             "the clipboard and in ONBOARDING_MISSION.md";
                    data["delivered"] = "clipboard";
                }
            } else {
                status = "mission delivered to the " + provider + " chat";
                data["delivered"] = delivered;
            }
        } else {
            status = "chat.send failed (" + app().ide().last_error() +
                     ") — mission saved to ONBOARDING_MISSION.md and copied "
                     "to the clipboard";
            win::set_clipboard_text(win::to_wide(mission));
            data["delivered"] = "clipboard";
        }
    } else {
        win::set_clipboard_text(win::to_wide(mission));
        status = "no IDE connected — mission saved to ONBOARDING_MISSION.md "
                 "and copied to the clipboard; paste it into Bob's chat";
        data["delivered"] = "clipboard";
    }

    data["status"] = status;
    return ok_result(status + "\n\nrecon:\n" + recon, data);
}

// Ordered tour stops for a guided codebase walkthrough: README first, then
// manifests, entry points, and one representative file per top-level dir.
Result codebase_tour(const nlohmann::json& a) {
    const std::string p = a.value("path", "");
    const fs::path root = p.empty() ? resolve("") : resolve(p);
    std::error_code ec;
    if (!fs::is_directory(root, ec))
        return fail("not a directory: " + narrow(root));

    struct Stop {
        std::string file;
        std::string why;
        int score;
    };
    std::vector<Stop> stops;
    std::map<std::string, std::string> dir_rep;  // first source file per top dir

    const auto is_source = [](const fs::path& f) {
        static const char* kExt[] = {".cpp", ".h",   ".hpp", ".c",   ".ts",
                                     ".tsx", ".js",  ".jsx", ".py",  ".java",
                                     ".go",  ".rs",  ".cs",  ".kt",  ".ps1"};
        const std::string e = f.extension().string();
        for (const char* k : kExt)
            if (e == k) return true;
        return false;
    };
    const auto is_manifest = [](const std::string& n) {
        static const char* kM[] = {
            "package.json",  "pom.xml",    "cmakelists.txt", "go.mod",
            "cargo.toml",    "pyproject.toml", "requirements.txt",
            "build.gradle",  "composer.json",  "pubspec.yaml",
        };
        for (const char* k : kM)
            if (n == k) return true;
        return n.size() > 4 && n.substr(n.size() - 4) == ".sln";
    };
    const auto is_entry = [](const std::string& stem) {
        static const char* kE[] = {"main",  "index", "app",     "server",
                                   "program", "manage", "mod", "lib",
                                   "extension"};
        for (const char* k : kE)
            if (stem == k) return true;
        return false;
    };

    int seen = 0;
    std::function<void(const fs::path&, int, const std::string&)> walk =
        [&](const fs::path& d, int depth, const std::string& top) {
            if (depth > 4 || seen > 900) return;
            for (const auto& e : fs::directory_iterator(d, ec)) {
                if (++seen > 900) return;
                const auto name = e.path().filename().string();
                if (name == ".git" || name == "node_modules" ||
                    name == "vcpkg_installed" || name == "dist" ||
                    name == ".next" || name == "target")
                    continue;
                const std::string rel =
                    narrow(fs::relative(e.path(), root, ec));
                const std::string topdir =
                    depth == 0 && e.is_directory(ec) ? name : top;
                if (e.is_directory(ec)) {
                    walk(e.path(), depth + 1, topdir);
                    continue;
                }
                std::string low = name;
                std::transform(low.begin(), low.end(), low.begin(),
                               [](unsigned char c) { return (char)tolower(c); });
                const std::string stem =
                    e.path().stem().string().size() ? [&] {
                        std::string s = e.path().stem().string();
                        std::transform(s.begin(), s.end(), s.begin(),
                                       [](unsigned char c) {
                                           return (char)tolower(c);
                                       });
                        return s;
                    }()
                                                    : low;
                if (low.rfind("readme", 0) == 0)
                    stops.push_back({rel, "readme — project overview", 100});
                else if (is_manifest(low))
                    stops.push_back({rel, "manifest — stack and dependencies", 90});
                else if (is_entry(stem))
                    stops.push_back({rel, "entry point", 80});
                if (!topdir.empty() && is_source(e.path()) &&
                    dir_rep.find(topdir) == dir_rep.end())
                    dir_rep[topdir] = rel;
            }
        };
    walk(root, 0, "");

    for (const auto& [dir, file] : dir_rep)
        stops.push_back({file, "representative file of " + dir + "/", 50});

    std::sort(stops.begin(), stops.end(),
              [](const Stop& a, const Stop& b) { return a.score > b.score; });
    stops.erase(std::unique(stops.begin(), stops.end(),
                            [](const Stop& a, const Stop& b) {
                                return a.file == b.file;
                            }),
                stops.end());
    if (stops.size() > 8) stops.resize(8);

    nlohmann::json arr = nlohmann::json::array();
    std::ostringstream text;
    text << "Tour stops for " << narrow(root) << ":\n";
    for (const auto& s : stops) {
        arr.push_back({{"file", s.file}, {"why", s.why}});
        text << "  - " << s.file << " (" << s.why << ")\n";
    }
    return ok_result(text.str(), {{"stops", std::move(arr)},
                                  {"repo", narrow(root)}});
}

// Run one setup/build/test command inside the repo, then tick the matching
// checkbox in ONBOARDING_CHECKLIST.md and append to its verification log.
Result verify_setup(const nlohmann::json& a) {
    const std::string p = a.value("path", "");
    const fs::path root = p.empty() ? resolve("") : resolve(p);
    std::error_code ec;
    if (!fs::is_directory(root, ec))
        return fail("not a directory: " + narrow(root));
    const std::string label = a.value("label", "");
    const std::string command = a.value("command", "");
    if (command.empty()) return fail("missing command");

    Result r =
        execute_command_native(command, narrow(root), a.value("timeout_ms", 300000));
    const std::string tail = r.output.size() > 1500
                                 ? "…" + r.output.substr(r.output.size() - 1500)
                                 : r.output;

    // Tick the checklist line whose text contains the label (case-insensitive).
    const fs::path cf = root / "ONBOARDING_CHECKLIST.md";
    std::string contents;
    {
        std::ifstream in(cf, std::ios::binary);
        if (in) contents.assign(std::istreambuf_iterator<char>(in), {});
    }
    if (contents.empty()) contents = "# Onboarding checklist\n\n";
    auto ci_find = [](const std::string& hay, const std::string& needle) {
        auto it = std::search(hay.begin(), hay.end(), needle.begin(),
                              needle.end(), [](char x, char y) {
                                  return tolower((unsigned char)x) ==
                                         tolower((unsigned char)y);
                              });
        return it != hay.end();
    };
    if (r.ok && !label.empty()) {
        std::istringstream in(contents);
        std::ostringstream fixed;
        std::string line;
        bool ticked = false;
        while (std::getline(in, line)) {
            if (!ticked && line.find("[ ]") != std::string::npos &&
                ci_find(line, label)) {
                line.replace(line.find("[ ]"), 3, "[x]");
                ticked = true;
            }
            fixed << line << "\n";
        }
        contents = fixed.str();
    }
    contents += std::format("\n- verification: `{}` → {}\n", command,
                            r.ok ? "PASS" : "FAIL");
    {
        std::ofstream f(cf, std::ios::binary | std::ios::trunc);
        f << contents;
    }

    return ok_result(std::format("{}: {}\n{}", r.ok ? "PASS" : "FAIL", label,
                                 tail),
                     {{"passed", r.ok},
                      {"label", label},
                      {"command", command},
                      {"output_tail", tail},
                      {"checklist_file", narrow(cf)}});
}

// Speak a short narration aloud through the robot voice — used for guided
// tours and progress updates. Utterances queue and play in order.
Result speak_tool(const nlohmann::json& a) {
    const std::string text = a.value("text", "");
    if (text.empty()) return fail("missing text");
    voice::speak_async(text);
    return ok_result("speaking");
}

// Probe the configured backend (java-backend / FastAPI relay): hits
// /api/health then /api/models and reports which providers are live vs
// simulation — the quick way to verify the backend's env keys.
Result backend_status(const nlohmann::json& a) {
    const std::string base =
        a.value("base_url", std::string(config().backend.base_url));
    if (base.empty()) return fail("backend base_url is empty");
    const std::string key = a.value("bearer", config().backend.api_key);
    const std::string auth =
        key.empty() ? "" : " -H \"Authorization: Bearer " + key + "\"";

    // java-backend serves /api/health; the FastAPI backend only has
    // /api/link/health — try both so "up" means up on either flavour.
    auto h = execute_command_native(
        "curl -s -o NUL -w \"%{http_code}\" -m 8" + auth + " \"" + base +
            "/api/health\"",
        "", 12000);
    const bool health_ok = h.ok && h.output.find("200") != std::string::npos;
    std::string flavour;
    if (health_ok) {
        flavour = "java-backend";
    } else {
        auto l = execute_command_native(
            "curl -s -o NUL -w \"%{http_code}\" -m 8" + auth + " \"" + base +
                "/api/link/health\"",
            "", 12000);
        if (!(l.ok && l.output.find("200") != std::string::npos))
            return fail("backend unreachable at " + base + " — " +
                        (h.ok ? "HTTP " + h.output : h.output.substr(0, 160)));
        flavour = "fastapi";
    }

    std::string out = "backend up at " + base + " (" + flavour + ")";
    auto m = execute_command_native(
        "curl -s -m 8" + auth + " \"" + base + "/api/models\"", "", 12000);
    if (m.ok) {
        const auto j = nlohmann::json::parse(m.output, nullptr, false);
        if (j.is_object() && j.contains("models")) {
            out += "\nproviders:";
            for (const auto& mod : j["models"]) {
                std::string st = mod.value("status", "");
                if (st.empty()) st = mod.value("availability", "?");
                out += "\n  " + mod.value("id", std::string("?")) + ": " + st;
                if (mod.value("isDefault", mod.value("default", false)))
                    out += " (default)";
            }
            if (j.contains("default_model"))
                out += "\ndefault model: " + j["default_model"].get<std::string>();
        }
    }
    return ok_result(out);
}

// Scan the repo's TRACKED files for leaked API keys/credentials — the check to
// run before pushing to GitHub. Matches common provider key shapes plus bare
// 32-hex secrets (AssemblyAI-style). Values are masked in the report.
Result secret_scan(const nlohmann::json& a) {
    const std::string p = a.value("path", "");
    const fs::path root = p.empty() ? resolve("") : resolve(p);
    // Provider-shaped keys (gitleaks-style) plus a generic 32-hex catch-all.
    const std::string pat =
        "csk-[A-Za-z0-9]{20,}|"                       // Cerebras
        "sk-proj-[A-Za-z0-9_-]{20,}|sk-ant-[A-Za-z0-9_-]{20,}|"
        "sk-or-v1-[0-9a-f]{20,}|sk-[A-Za-z0-9]{32,}|" // OpenAI/Anthropic/OpenRouter
        "AIza[0-9A-Za-z_-]{35}|"                      // Google
        "gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{30,}|" // GitHub
        "glpat-[A-Za-z0-9_-]{20,}|"                   // GitLab
        "xox[baprs]-[0-9A-Za-z-]{10,}|"               // Slack
        "hooks\\.slack\\.com/services/[A-Za-z0-9_/]{20,}|" // Slack webhook
        "SG\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}|"   // SendGrid
        "[sr]k_live_[0-9a-zA-Z]{24,}|"                // Stripe
        "AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|"          // AWS
        "dop_v1_[0-9a-f]{64}|npm_[A-Za-z0-9]{36}|"    // DigitalOcean / npm
        "pypi-[A-Za-z0-9_-]{50,}|hf_[A-Za-z0-9]{30,}|" // PyPI / HuggingFace
        "shpat_[0-9a-fA-F]{32}|sq0[a-z]{3}-[A-Za-z0-9_-]{20,}|" // Shopify/Square
        "BEGIN [A-Z ]*PRIVATE KEY|"                   // PEM blocks
        "[0-9a-fA-F]{32}";                            // generic 32-hex (AssemblyAI…)
    auto r = execute_command_native("git grep -n -I -E \"" + pat + "\"",
                                    narrow(root), 30000);
    // git grep exit codes: 0 = hits, 1 = clean, anything else = real failure —
    // surface it instead of masquerading as "clean".
    if (!r.ok && r.output.empty())
        return ok_result("clean — no key-shaped strings in tracked files");
    if (!r.ok)
        return fail("secret scan failed: " + r.output.substr(0, 300));

    // Mask every matched token: keep file:line + key prefix, hide the rest.
    std::string out;
    const std::regex any_key(pat);
    size_t cursor = 0;
    for (std::sregex_iterator it(r.output.begin(), r.output.end(), any_key),
                              end;
         it != end; ++it) {
        out.append(r.output, cursor, (size_t)it->position() - cursor);
        const std::string s = it->str();
        out += s.substr(0, std::min<size_t>(8, s.size())) + "***";
        cursor = it->position() + it->length();
    }
    out.append(r.output, cursor, std::string::npos);
    int lines = 1 + (int)std::count(out.begin(), out.end(), '\n');
    return ok_result(std::format("⚠ {} possible credential hit(s):\n{}", lines,
                                 out));
}

// Stage + commit the repo the IDE has open (git add -A && git commit).
Result git_commit(const nlohmann::json& a) {
    const std::string msg =
        a.value("message", std::string("chore: update via Argos"));
    const std::string p = a.value("path", "");
    const fs::path root = p.empty() ? resolve("") : resolve(p);
    auto st = execute_command_native("git status --porcelain", narrow(root), 20000);
    if (!st.ok) return fail("not a git repo: " + narrow(root));
    if (st.output.empty()) return ok_result("nothing to commit — tree is clean");
    auto add = execute_command_native("git add -A", narrow(root), 60000);
    if (!add.ok) return fail("git add failed: " + add.output.substr(0, 200));
    std::string safe = msg;
    std::replace(safe.begin(), safe.end(), '"', '\'');
    auto com = execute_command_native("git commit -m \"" + safe + "\"",
                                      narrow(root), 60000);
    if (!com.ok) return fail("git commit failed: " + com.output.substr(0, 300));
    return ok_result("committed: " + com.output);
}

// Pop the glass summary panel: renders ONBOARDING.md (markdown) plus the
// project's mermaid diagram in the floating overlay widget.
Result show_onboarding_summary(const nlohmann::json& a) {
    const std::string p = a.value("path", "");
    const fs::path root = p.empty() ? resolve("") : resolve(p);
    std::error_code ec;
    fs::path doc = root / "ONBOARDING.md";
    if (const std::string f = a.value("file", ""); !f.empty()) doc = resolve(f);
    if (!fs::is_regular_file(doc, ec))
        return fail("no ONBOARDING.md yet — run onboard_project first");

    std::ifstream in(doc, std::ios::binary);
    std::string md{std::istreambuf_iterator<char>(in),
                   std::istreambuf_iterator<char>()};

    // Pull ```mermaid blocks out — they get a dedicated diagram pane.
    std::string mermaid, stripped;
    size_t pos = 0;
    while (pos < md.size()) {
        const size_t fence = md.find("```mermaid", pos);
        if (fence == std::string::npos) {
            stripped += md.substr(pos);
            break;
        }
        stripped += md.substr(pos, fence - pos);
        const size_t nl = md.find('\n', fence);
        const size_t end = nl == std::string::npos
                               ? std::string::npos
                               : md.find("```", nl + 1);
        if (nl == std::string::npos || end == std::string::npos) {
            stripped += md.substr(fence);
            break;
        }
        if (mermaid.empty())
            mermaid = md.substr(nl + 1, end - nl - 1);
        pos = end + 3;
        while (pos < md.size() && (md[pos] == '\r' || md[pos] == '\n')) ++pos;
    }

    // Connected IDEs — fleet utilization across every live bridge, so the
    // report shows which editor analyzed the repo and how its chat was driven.
    std::string fleet;
    const auto& current = app().ide().endpoint();
    for (const auto& ep : bridge::IdeBridge::enumerate()) {
        if (fleet.empty()) {
            fleet = "## Connected IDEs\n\n"
                    "| IDE | Version | Port | Workspace | Chat delivery |\n"
                    "|---|---|---|---|---|\n";
        }
        std::string delivery = "generic — paste+Enter";
        if (const auto pr = bridge::IdeBridge::call_on(ep, "chat.providers")) {
            for (const auto& prov :
                 pr->value("providers", nlohmann::json::array())) {
                if (!prov.value("installed", false)) continue;
                delivery =
                    prov.value("name", prov.value("id", std::string("chat")));
                delivery += prov.value("programmaticSend", false)
                                ? " — direct send"
                                : " — paste+Enter";
                break;
            }
        }
        const auto ws = fs::path(ep.workspace).filename().string();
        fleet += "| " + ep.ide + " | " +
                 (ep.version.empty() ? "—" : ep.version) + " | " +
                 std::to_string(ep.port) + " | " +
                 (ws.empty() ? "—" : ws) + " | " + delivery +
                 (ep.pid == current.pid && ep.port == current.port
                      ? " **← current**"
                      : "") +
                 " |\n";
    }
    if (!fleet.empty()) fleet += "\n";

    const std::string title =
        "Onboarding — " + doc.parent_path().filename().string();
    app().summary().show(title, fleet + stripped, mermaid, doc.parent_path());
    return ok_result("summary panel shown" +
                     (mermaid.empty() ? std::string(" (no mermaid diagram found)")
                                      : std::string()));
}

// Present a numbered plan (with per-IDE assignments) and stop — the model
// must wait for the user to confirm before calling any executing tools.
Result propose_plan(const nlohmann::json& a) {
    if (!a.contains("steps") || !a["steps"].is_array() || a["steps"].empty())
        return fail("propose_plan needs a non-empty steps[] array");
    std::string out = "Proposed plan (reply \"proceed\" / \"go\" / \"yes\" to "
                      "execute):\n";
    int i = 0;
    for (const auto& s : a["steps"]) {
        const std::string desc = s.value("task", s.value("text",
                                  s.is_string() ? s.get<std::string>() : "?"));
        const std::string tgt  = s.value("target", std::string("argos"));
        out += std::format("{}. [{}] {}\n", ++i, tgt, desc);
    }
    return ok_result(
        out +
        "\nPlan presented. STOP — do not call any executing tools until the "
        "user confirms.");
}

// Fleet-wide status: every registered IDE gets an ide.status probe so the
// model can report real per-IDE progress (active file, open tabs, chat
// providers, workspace) instead of a single-endpoint connected flag.
Result ide_status_fleet() {
    const auto all = bridge::IdeBridge::enumerate();
    if (all.empty())
        return ok_result(R"({"ides":[],"connected":false})",
                         {{"connected", false},
                          {"ides", nlohmann::json::array()}});
    nlohmann::json ides = nlohmann::json::array();
    const int cur_pid =
        app().ide().connected() ? app().ide().endpoint().pid : 0;
    for (auto& ep : all) {
        // Keep the per-IDE payload compact — tool results are truncated inside
        // the prompt context, so bulky fields (full tab lists etc.) would push
        // the useful bits of later entries out of the model's view.
        nlohmann::json e = {{"ide", ep.ide},
                            {"workspace", ep.workspace},
                            {"current", ep.pid == cur_pid}};
        if (auto r = bridge::IdeBridge::call_on(ep, "ide.status", {})) {
            if (auto v = r->value("activeFile", nlohmann::json{});
                v.is_string() && !v.get<std::string>().empty())
                e["activeFile"] =
                    fs::path(v.get<std::string>()).filename().string();
            if (auto v = r->value("openTabs", nlohmann::json{}); v.is_array())
                e["openTabs"] = v.size();
            if (auto v = r->value("chatProviders", nlohmann::json{});
                v.is_array()) {
                nlohmann::json provs = nlohmann::json::array();
                for (const auto& pr : v)
                    if (pr.is_object())
                        provs.push_back(pr.value(
                            "id", pr.value("name", std::string("?"))));
                e["chatProviders"] = std::move(provs);
            }
            e["reachable"] = true;
        } else {
            e["reachable"] = false;
        }
        ides.push_back(std::move(e));
    }
    // Terminal windows join the fleet as paste-only targets (AI coder CLIs).
    nlohmann::json tj = nlohmann::json::array();
    for (const auto& tw : find_terminals())
        tj.push_back({{"title", tw.title}, {"pid", tw.pid},
                      {"target", "terminal"}});

    // Human-readable fleet report — this string is what the chatbox shows.
    std::string readable;
    for (const auto& e : ides) {
        readable += "• " + e.value("ide", std::string("?"));
        readable += " — " + e.value("workspace", std::string(""));
        if (e.value("reachable", false)) {
            if (e.contains("openTabs"))
                readable += ", " + std::to_string((int)e["openTabs"]) + " tab(s)";
            if (e.contains("activeFile"))
                readable += ", editing " +
                            e["activeFile"].get<std::string>();
            if (e.contains("chatProviders"))
                readable += ", providers: " + e["chatProviders"].dump();
            if (e.value("current", false)) readable += " [bound]";
        } else {
            readable += " — unreachable";
        }
        readable += "\n";
    }
    for (const auto& t : tj)
        readable += "• terminal: " + t.value("title", std::string("?")) + "\n";

    // AI coding CLIs (npm-installed) — running ones are dispatchable through
    // their terminal window; installed ones can be launched with a prompt.
    nlohmann::json cj = nlohmann::json::array();
    for (const auto& c : cli_agents()) {
        cj.push_back({{"name", c.name},
                      {"bin", c.bin},
                      {"pid", (unsigned long)c.pid},
                      {"running", c.running},
                      {"installed", c.installed}});
        if (c.running)
            readable += "• cli: " + c.name + " (running, pid " +
                        std::to_string(c.pid) + ")\n";
    }
    std::string installed_only;
    for (const auto& c : cli_agents()) {
        if (c.running || !c.installed) continue;
        if (!installed_only.empty()) installed_only += ", ";
        installed_only += c.bin;
    }
    if (!installed_only.empty())
        readable += "• clis installed (not running): " + installed_only + "\n";
    return ok_result(readable, {{"connected", true},
                                {"ides", ides},
                                {"terminals", tj},
                                {"clis", cj}});
}

// Explicit pause — the model calls this between dispatching work to IDEs and
// polling their status, giving slower IDE assistants time to produce output.
Result wait_seconds(const nlohmann::json& a) {
    const double secs = std::clamp(a.value("seconds", 3.0), 0.1, 60.0);
    ::Sleep((DWORD)std::lround(secs * 1000.0));
    return ok_result(std::format("waited {:.0f}s", secs));
}

// ──────────────────────────────────────────────────────────────────────────
// Native dispatch
// ──────────────────────────────────────────────────────────────────────────

Result via_native(const std::string& tool, const nlohmann::json& args) {
    const auto& a = args;
    try {
        if (tool == "read_file")
            return read_file_native(resolve(a.value("path", "")),
                                    a.value("offset", 0), a.value("limit", 0));
        if (tool == "write_file")
            return write_file_native(resolve(a.value("path", "")), a.value("content", ""));
        if (tool == "apply_diff")
            return apply_diff_native(resolve(a.value("path", "")), a.value("diff", ""));
        if (tool == "insert_content")
            return insert_content_native(resolve(a.value("path", "")), a.value("line", 0),
                                         a.value("content", ""));
        if (tool == "list_files") {
            const std::string p = a.value("path", "");
            return list_files_native(p.empty() ? fs::current_path() : resolve(p));
        }
        if (tool == "file_stat") return file_stat_native(resolve(a.value("path", "")));
        if (tool == "search_files") {
            const std::string p = a.value("path", "");
            return search_files_native(p.empty() ? fs::current_path() : resolve(p),
                                       a.value("pattern", ""), a.value("glob", ""),
                                       a.value("maxResults", 200));
        }
        if (tool == "rename_file")
            return rename_native(resolve(a.value("from", "")), resolve(a.value("to", "")),
                                 a.value("overwrite", false));
        if (tool == "copy_file")
            return copy_native(resolve(a.value("from", "")), resolve(a.value("to", "")),
                               a.value("overwrite", true));
        if (tool == "make_dir") {
            const fs::path dir = resolve(a.value("path", ""));
            std::error_code ec;
            fs::create_directories(dir, ec);
            if (ec) return fail("mkdir failed: " + ec.message());
            return ok_result("created " + narrow(dir));
        }
        if (tool == "delete_file") {
            const bool confirmed =
                a.value("confirmed", false) || config().tools.allow_destructive;
            if (!confirmed) return fail("delete requires confirmed=true");
            const fs::path p = resolve(a.value("path", ""));
            std::error_code ec;
            fs::remove_all(p, ec);
            if (ec) return fail("delete failed: " + ec.message());
            return ok_result("deleted " + narrow(p));
        }
        if (tool == "execute_command")
            return execute_command_native(a.value("command", ""), a.value("cwd", ""),
                                          a.value("timeout_ms", 120000));
        if (tool == "ide_status")
            return ok_result(R"({"ide":null,"connected":false})",
                             {{"connected", false}});
        return fail("tool requires the IDE bridge: " + tool);
    } catch (const std::exception& e) {
        return fail(std::string("tool error: ") + e.what());
    }
}

}  // namespace

// ──────────────────────────────────────────────────────────────────────────
// Public API
// ──────────────────────────────────────────────────────────────────────────

nlohmann::json terminal_task_claim(unsigned long pid) {
    auto doc = terminal_tasks_read();
    for (auto& t : doc["tasks"]) {
        if (t.value("status", "") == "pending" &&
            (pid == 0 || (unsigned long)t.value("pid", 0) == pid)) {
            t["status"] = "claimed";
            terminal_tasks_write(doc);
            return t;
        }
    }
    return nullptr;
}

nlohmann::json terminal_task_complete(const std::string& id,
                                      const std::string& result) {
    auto doc = terminal_tasks_read();
    for (auto& t : doc["tasks"]) {
        if (t.value("id", "") == id) {
            t["status"] = "done";
            t["result"] = result;
            terminal_tasks_write(doc);
            return t;
        }
    }
    return nullptr;
}

nlohmann::json terminal_task_pending() {
    auto doc = terminal_tasks_read();
    nlohmann::json out = nlohmann::json::array();
    for (auto& t : doc["tasks"])
        if (t.value("status", "") == "pending") out.push_back(t);
    return out;
}

nlohmann::json fleet_summary() {
    nlohmann::json ides = nlohmann::json::array();
    for (const auto& ep : bridge::IdeBridge::registered())
        ides.push_back({{"name", ep.ide},
                        {"pid", ep.main_pid ? ep.main_pid : ep.pid},
                        {"workspace", ep.workspace}});
    nlohmann::json terms = nlohmann::json::array();
    std::set<DWORD> seen;
    for (const auto& tw : find_terminals()) {
        if (!seen.insert(tw.pid).second) continue;
        std::string t = tw.title;
        while (!t.empty() && t.back() == ' ') t.pop_back();
        terms.push_back({{"title", t}, {"pid", tw.pid}});
    }
    nlohmann::json clis = nlohmann::json::array();
    for (const auto& c : cli_agents())
        clis.push_back({{"name", c.name},
                        {"bin", c.bin},
                        {"pid", (unsigned long)c.pid},
                        {"running", c.running},
                        {"installed", c.installed},
                        {"headline", c.headline}});
    return {{"ides", ides}, {"terminals", terms}, {"clis", clis}};
}

Result execute(std::string_view tool, const nlohmann::json& args) {
    const std::string name(tool);
    // Orchestration tools — run natively (onboard uses the bridge itself for
    // chat delivery; verify/tour/speak are local by nature).
    if (name == "onboard_project") return onboard_project(args);
    if (name == "codebase_tour") return codebase_tour(args);
    if (name == "verify_setup") return verify_setup(args);
    if (name == "speak") return speak_tool(args);
    if (name == "show_onboarding_summary") return show_onboarding_summary(args);
    if (name == "backend_status") return backend_status(args);
    if (name == "ide_dispatch") return ide_dispatch(args);
    if (name == "ide_status") return ide_status_fleet();
    if (name == "wait_seconds") return wait_seconds(args);
    if (name == "propose_plan") return propose_plan(args);
    if (name == "secret_scan") return secret_scan(args);
    if (name == "git_commit") return git_commit(args);
    if (name == "delete_file") {
        // destructive ops go through the confirmation gate either way
        nlohmann::json patched = args;
        if (config().tools.allow_destructive) patched["confirmed"] = true;
        return app().ide().connected() ? via_bridge(name, patched) : via_native(name, patched);
    }
    // Bridge tools run whenever ANY IDE is registered — via_bridge falls back
    // to the first live endpoint when nothing is bound.
    const bool any_ide =
        app().ide().connected() || !bridge::IdeBridge::enumerate().empty();
    return any_ide ? via_bridge(name, args) : via_native(name, args);
}

const nlohmann::json& schemas() {
    static const nlohmann::json s = [] {
    try {
    return nlohmann::json::parse(R"JSON([
{"type":"function","function":{"name":"read_file","description":"Read a file's contents. Output is line-numbered ('1 | ...'). Absolute path or workspace-relative.","parameters":{"type":"object","properties":{"path":{"type":"string"},"offset":{"type":"integer","description":"0-based first line"},"limit":{"type":"integer","description":"max lines, 0 = all"}},"required":["path"]}}},
{"type":"function","function":{"name":"write_file","description":"Create or fully overwrite a file. Parent directories are created.","parameters":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}}},
{"type":"function","function":{"name":"apply_diff","description":"Surgical edits via <<<<<<< SEARCH / :start_line:N / ======= / >>>>>>> REPLACE blocks. SEARCH must match existing content exactly (whitespace-fuzzy fallback exists). Read the file first if unsure.","parameters":{"type":"object","properties":{"path":{"type":"string"},"diff":{"type":"string"}},"required":["path","diff"]}}},
{"type":"function","function":{"name":"insert_content","description":"Insert text at a 0-based line number in a file.","parameters":{"type":"object","properties":{"path":{"type":"string"},"line":{"type":"integer"},"content":{"type":"string"}},"required":["path","line","content"]}}},
{"type":"function","function":{"name":"list_files","description":"List a directory's entries (files and dirs).","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}},
{"type":"function","function":{"name":"search_files","description":"Regex search across file contents under a directory. Returns file/line/snippet matches.","parameters":{"type":"object","properties":{"pattern":{"type":"string"},"path":{"type":"string"},"glob":{"type":"string","description":"e.g. *.cpp or **/*.ts"},"maxResults":{"type":"integer"}},"required":["pattern"]}}},
{"type":"function","function":{"name":"file_stat","description":"Check existence/kind/size/mtime of a path.","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}},
{"type":"function","function":{"name":"rename_file","description":"Rename or move a file/directory.","parameters":{"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"},"overwrite":{"type":"boolean"}},"required":["from","to"]}}},
{"type":"function","function":{"name":"copy_file","description":"Copy a file or directory tree.","parameters":{"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"},"overwrite":{"type":"boolean"}},"required":["from","to"]}}},
{"type":"function","function":{"name":"make_dir","description":"Create a directory (recursive).","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}},
{"type":"function","function":{"name":"delete_file","description":"Delete a file or directory (to recycle bin when the IDE bridge is active). Requires confirmed=true.","parameters":{"type":"object","properties":{"path":{"type":"string"},"confirmed":{"type":"boolean"}},"required":["path","confirmed"]}}},
{"type":"function","function":{"name":"execute_command","description":"Run a shell command in the IDE terminal (or cmd.exe when no IDE is connected) and capture its output.","parameters":{"type":"object","properties":{"command":{"type":"string"},"cwd":{"type":"string"},"timeout_ms":{"type":"integer"}},"required":["command"]}}},
{"type":"function","function":{"name":"open_in_editor","description":"Open a file in the editor at an optional line/column (IDE bridge only).","parameters":{"type":"object","properties":{"path":{"type":"string"},"line":{"type":"integer"},"col":{"type":"integer"}},"required":["path"]}}},
{"type":"function","function":{"name":"get_diagnostics","description":"Get compiler/linter problems for a file or the whole workspace (IDE bridge only).","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}},
{"type":"function","function":{"name":"list_symbols","description":"List document symbols (functions, classes) for a file (IDE bridge only).","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}},
{"type":"function","function":{"name":"ide_status","description":"Status of EVERY connected IDE (the whole fleet): name, workspace, active file, open tabs, detected chat providers, and whether each is reachable. Use after dispatching tasks to report each IDE's progress.","parameters":{"type":"object","properties":{}}}},
{"type":"function","function":{"name":"wait_seconds","description":"Pause for N seconds (max 60). Use between dispatching tasks to IDEs and checking ide_status so slow IDE assistants have time to work.","parameters":{"type":"object","properties":{"seconds":{"type":"number","description":"0.1-60 (default 3)"}}}}},
{"type":"function","function":{"name":"propose_plan","description":"Present a numbered plan to the user and STOP — required before any multi-step or multi-IDE job. Each step assigns a target ('bob','code','all','argos'). Do NOT execute until the user confirms.","parameters":{"type":"object","properties":{"steps":{"type":"array","items":{"type":"object","properties":{"target":{"type":"string"},"task":{"type":"string"}},"required":["task"]}}},"required":["steps"]}}},
{"type":"function","function":{"name":"save_file","description":"Save a file's open editor buffer (IDE bridge only).","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}},
{"type":"function","function":{"name":"save_all","description":"Save all open editor buffers (IDE bridge only).","parameters":{"type":"object","properties":{}}}},
{"type":"function","function":{"name":"ide_chat","description":"Send a prompt to the IDE's AI chat assistant (Copilot, Cascade, Cline, Roo, Cody...) via the installed Argos bridge extension. Use it to hand work to the IDE's own assistant or read it back.","parameters":{"type":"object","properties":{"text":{"type":"string","description":"The prompt text to deliver to the chatbox"},"target":{"type":"string","description":"Which IDE: 'current' (default), 'all', a pid, or an IDE-name substring like 'bob' or 'code'"},"provider":{"type":"string","description":"optional: copilot|continue|cline|roo|cody|generic"},"submit":{"type":"boolean","description":"press Enter after inserting (default true)"}},"required":["text"]}}},
{"type":"function","function":{"name":"ide_dispatch","description":"Fan out a plan of independent tasks across MULTIPLE connected IDEs at once — e.g. send one task to IBM Bob and a different one to VS Code in a single call. Each task targets an IDE by name substring ('bob', 'code'), pid, or 'all'. Returns per-IDE delivery results (command-delivered vs pasted).","parameters":{"type":"object","properties":{"tasks":{"type":"array","description":"The plan — one entry per IDE task","items":{"type":"object","properties":{"target":{"type":"string","description":"IDE selector: name substring ('bob','code'), pid, or 'all' (default: current IDE)"},"text":{"type":"string","description":"The task/prompt for that IDE's AI assistant"},"provider":{"type":"string","description":"optional chat provider id e.g. 'copilot'"},"submit":{"type":"boolean","description":"press Enter after inserting (default true)"}},"required":["text"]}}},"required":["tasks"]}}},
{"type":"function","function":{"name":"ide_chat_new","description":"Start a fresh AI chat session in the IDE (IDE bridge only).","parameters":{"type":"object","properties":{"provider":{"type":"string","description":"optional provider id"}}}}},
{"type":"function","function":{"name":"onboard_project","description":"Onboard a developer onto a repository. Does a quick recon of the repo (layout, manifests, recent git history, top contributors), writes ONBOARDING_CHECKLIST.md, then hands a structured onboarding mission to the IDE's AI assistant (IBM Bob): analyze the codebase, write AGENTS.md + ONBOARDING.md, create a setup script, extend the checklist. Use when the user asks to be onboarded, get up to speed, or understand a repo. Afterwards offer to read ONBOARDING.md aloud once Bob finishes writing it.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"Repo root — defaults to the IDE workspace or current directory"}}}}},
{"type":"function","function":{"name":"codebase_tour","description":"Return the ordered tour stops (key files + why) for a guided codebase walkthrough. For each stop: open_in_editor the file, then speak a 1-2 sentence explanation of what it does. Use when the user asks for a tour, walkthrough, or 'show me around' a repo.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"Repo root — defaults to the IDE workspace or current directory"}}}}},
{"type":"function","function":{"name":"verify_setup","description":"Run one setup/build/test command inside a repo and record PASS/FAIL in ONBOARDING_CHECKLIST.md — ticks the checkbox whose text contains 'label' on success. Use to prove the onboarding environment actually works.","parameters":{"type":"object","properties":{"path":{"type":"string"},"label":{"type":"string","description":"Checklist item to tick, e.g. 'Project builds' or 'Tests pass'"},"command":{"type":"string","description":"The command to run"},"timeout_ms":{"type":"integer"}},"required":["command","label"]}}},
{"type":"function","function":{"name":"speak","description":"Speak a short narration aloud through the robot's voice right now — for guided tours and progress updates. Keep each call to 1-2 short sentences; utterances queue and play in order.","parameters":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}},
{"type":"function","function":{"name":"show_onboarding_summary","description":"Open the floating glass summary panel showing ONBOARDING.md rendered as markdown plus the project's mermaid architecture diagram. Use after Bob finishes the onboarding mission, or when the user asks to see the summary/report.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"Repo root containing ONBOARDING.md"},"file":{"type":"string","description":"Direct path to a markdown file (overrides path)"}}}}},
{"type":"function","function":{"name":"secret_scan","description":"Scan the repo's tracked files for leaked API keys and credentials (Cerebras/OpenAI/GitHub/AssemblyAI-style shapes). Use before pushing to GitHub. Returns masked matches with file:line.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"Repo root (default: current workspace)"}}}}},
{"type":"function","function":{"name":"git_commit","description":"Stage all changes and commit (git add -A + git commit) in the current repo. Use when the user asks to save/commit work. Never includes a message unless the user gave one or asked to commit.","parameters":{"type":"object","properties":{"message":{"type":"string","description":"Commit message (default: 'chore: update via Argos')"},"path":{"type":"string","description":"Repo root (default: current workspace)"}}}}},
{"type":"function","function":{"name":"backend_status","description":"Check the configured backend relay (java-backend / FastAPI): probes /api/health and /api/models, reporting each model provider's status (live vs simulation) and the default model. Use when the user asks whether the backend is up or which providers have keys configured.","parameters":{"type":"object","properties":{"base_url":{"type":"string","description":"Override backend URL (default: settings)"},"bearer":{"type":"string","description":"Optional bearer token (default: settings)"}}}}}
])JSON");
    } catch (const std::exception& e) {
        // A malformed literal must never take down a caller thread — degrade
        // to an empty tool list and say so loudly in the log instead.
        log::error(std::string("tool schemas JSON invalid: ") + e.what());
        return nlohmann::json::array();
    }
    }();
    return s;
}

}  // namespace argos::tools
