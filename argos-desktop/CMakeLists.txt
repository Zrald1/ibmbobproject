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
    # Phone link server (QR pairing + LAN command channel)
    ws2_32
    iphlpapi
    bcrypt
    # CreateDispatcherQueueController (required by WebView2 visual hosting)
    CoreMessaging
)

set_property(DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}" PROPERTY VS_STARTUP_PROJECT argos)

# Drop the built exe where it is easy to find next to the project, and stage the
# Three.js robot scene beside it so WebView2 can serve it as a virtual host.
add_custom_command(TARGET argos POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "$<TARGET_FILE:argos>"
            "${CMAKE_CURRENT_SOURCE_DIR}/bin/argos.exe"
    COMMAND ${CMAKE_COMMAND} -E copy_directory
            "${CMAKE_CURRENT_SOURCE_DIR}/assets"
            "${CMAKE_CURRENT_SOURCE_DIR}/bin/assets"
    COMMAND ${CMAKE_COMMAND} -E copy_directory
            "${CMAKE_CURRENT_SOURCE_DIR}/assets"
            "$<TARGET_FILE_DIR:argos>/assets"
    COMMENT "Staging argos.exe and the robot scene into bin/"
)
