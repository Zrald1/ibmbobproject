You are the onboarding engineer for this repository — a new developer must become productive today.

Argos (a desktop companion agent) did this quick recon:

repo root: C:/Users/Zrald/ibmbobproject/argos-desktop

Top level:
  [file] .clangd
  [file] .gitignore
  [dir]  assets
  [dir]  bin
  [dir]  build
  [file] build_clean_latest.txt
  [dir]  build_ninja
  [file] build_output.txt
  [file] build_output2.txt
  [file] build_output_latest.txt
  [file] CMakeLists.txt
  [file] CMakePresets.json
  [file] compile_commands.json
  [dir]  extension-ide
  [file] nul
  [file] ONBOARDING.md
  [dir]  scratch
  [dir]  src
  [file] TOOLS.md
  [file] vcpkg.json

File types: .cpp=20 .h=18 .js=8 .json=7 .txt=6 (no ext)=5 .map=5 .ts=5 .md=3 .cmake=2 .html=2 .ps1=2

--- CMakeLists.txt (excerpt) ---
cmake_minimum_required(VERSION 3.25)

project(argos_desktop
    VERSION 0.1.0
    DESCRIPTION "Argos Desktop — local AI companion for Windows"
    LANGUAGES CXX)

if(NOT MSVC)
    message(FATAL_ERROR "Argos Desktop targets MSVC on Windows.")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>DLL")

find_package(imgui CONFIG REQUIRED)
find_package(nlohmann_json CONFIG REQUIRED)
find_package(unofficial-webview2 CONFIG REQUIRED)
find_package(wil CONFIG REQUIRED)

file(GLOB_RECURSE ARGOS_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp")
file(GLOB_RECURSE ARGOS_HEADERS CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/*.h")

add_executable(argos WIN32 ${ARGOS_SOURCES} ${ARGOS_HEADERS})

target_include_directories(argos PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

target_link_libraries(argos PRIVATE
    imgui::imgui
    nlohmann_json::nlohmann_json
    unofficial::webview2::webview2
    WIL::WIL
)

target_compile_definitions(argos PRIVATE
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    _WIN32_WINNT=0x0A00
    WINVER=0x0A00
    ARGOS_VERSION="${PROJECT_VERSION}"
)

target_compile_options(argos PRIVATE
    /W4
    /permissive-
    /utf-8
    /Zc:__cplusplus
    /MP
    $<$<CONFIG:Release>:/O2 /GL>
)

target_link_options(argos PRIVATE
    $<$<CONFIG:Release>:/LTCG>
)

target_link_libraries(argos PRIVATE
    d3d11
    d3dcompiler
    dxgi
    dxguid
    dcomp
    dwmapi
    shcore
    winhttp
    windowscodecs
    ole32
    oleaut32
    uuid
    crypt32
    shell32
    shlwapi
    comdlg32
    uiautomationcore
    sapi
    version
    # Phone link


Your mission (use agent mode, subagents and document understanding as needed):
1. Analyze the codebase: purpose, tech stack, architecture, entry points, data flow, key modules.
2. Write AGENTS.md at the repo root — persistent project context for AI agents: build/test commands, conventions, key directories.
3. Write ONBOARDING.md at the repo root — start with a ```mermaid fenced block containing a mermaid "graph TD" architecture diagram of the project structure (top-level modules and how they connect), then: architecture map, module tour, exact setup/build/test commands, 3 suggested first tasks ranked by difficulty, an "unwritten conventions and gotchas" section (error-handling style, where validation lives, test patterns, naming, known traps), and a "who to ask" section mapping key modules to their top contributors.
4. If setup needs steps (dependency install, env vars, tooling), create a script (scripts/setup.ps1 or scripts/setup.sh) that performs them.
5. Append repo-specific items to ONBOARDING_CHECKLIST.md — Argos already wrote the standard items; keep its checkbox lines intact.
6. Finish with a 5-sentence spoken-style summary: what the project is, the stack, how to build and test it, and the recommended first task.