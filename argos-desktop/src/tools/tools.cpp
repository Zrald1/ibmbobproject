// Tool executor: OpenAI-format schemas + dispatch (bridge -> native fallback).
// See tools.h for the architecture notes.

#include "tools/tools.h"

#include <windows.h>

#include <algorithm>
#include <array>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <optional>
#include <regex>
#include <sstream>
#include <unordered_map>

#include "core/app.h"
#include "core/config.h"
#include "core/log.h"
#include "platform/win_util.h"

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

    std::wstring cmdline = L"cmd.exe /c " + win::to_wide(command);
    std::wstring workdir = cwd.empty() ? std::wstring() : fs::path(win::to_wide(cwd)).wstring();

    PROCESS_INFORMATION pi{};
    const BOOL launched = ::CreateProcessW(
        nullptr, cmdline.data(), nullptr, nullptr, TRUE,
        CREATE_NO_WINDOW, nullptr, workdir.empty() ? nullptr : workdir.c_str(), &si, &pi);
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
    return ok_result(narrow(path) + " (" + data["kind"].get<std::string>() + ")", std::move(data));
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
};

Result via_bridge(const std::string& tool, const nlohmann::json& args) {
    const auto it = kBridgeMethods.find(tool);
    if (it == kBridgeMethods.end()) return fail("unknown tool: " + tool);
    auto result = app().ide().call(it->second, args);
    if (!result) return fail("bridge: " + app().ide().last_error());
    return ok_result(result->dump(2), *result);
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

Result execute(std::string_view tool, const nlohmann::json& args) {
    const std::string name(tool);
    if (name == "delete_file") {
        // destructive ops go through the confirmation gate either way
        nlohmann::json patched = args;
        if (config().tools.allow_destructive) patched["confirmed"] = true;
        return app().ide().connected() ? via_bridge(name, patched) : via_native(name, patched);
    }
    return app().ide().connected() ? via_bridge(name, args) : via_native(name, args);
}

const nlohmann::json& schemas() {
    static const nlohmann::json s = nlohmann::json::parse(R"JSON([
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
{"type":"function","function":{"name":"ide_status","description":"Connected IDE info: app, workspace, active file, open tabs, detected chat providers.","parameters":{"type":"object","properties":{}}}},
{"type":"function","function":{"name":"save_file","description":"Save a file's open editor buffer (IDE bridge only).","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}}
])JSON");
    return s;
}

}  // namespace argos::tools
