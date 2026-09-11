cmake_minimum_required(VERSION 3.22.1)
project(argos)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Support 16 KB memory page sizes (required by Google Play for Android 15+)
# NDK r27 and lower need BOTH linker flags:
#   -Wl,-z,max-page-size=16384   (align ELF LOAD segments to 16 KB)
#   -Wl,-z,common-page-size=16384 (tell the linker the common page size is 16 KB)
add_link_options(
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

# Find Android log library (EGL/GLES no longer needed — rendering via WebView/Three.js)
find_library(log-lib log)
find_library(android-lib android)

# Cross-platform source dir (5 levels up from android/app/src/main/cpp/)
set(CROSS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../cpp/src_cross)

# Source files for Argos Android
set(ARGOS_SOURCES
    native_main.cpp
    platform_android.cpp
    # Shared core (platform-abstracted versions)
    ${CROSS_DIR}/agent_client_core.cpp
    ${CROSS_DIR}/argos_tools_core.cpp
    ${CROSS_DIR}/ui_inspector.cpp
)

add_library(argos SHARED ${ARGOS_SOURCES})

# 16 KB page size alignment for the argos library (NDK r27 needs both flags)
target_link_options(argos PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

target_include_directories(argos PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CROSS_DIR}
)

target_link_libraries(argos
    ${log-lib}
    ${android-lib}
)
