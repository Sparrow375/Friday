cmake_minimum_required(VERSION 3.22.1)
project(friday_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Configure GGML / llama.cpp / whisper.cpp flags for Android before fetching
set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "Disable Native compiler optimizations" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "Disable llamafile" FORCE)
set(GGML_AVX OFF CACHE BOOL "Disable AVX" FORCE)
set(GGML_AVX2 OFF CACHE BOOL "Disable AVX2" FORCE)
set(GGML_FMA OFF CACHE BOOL "Disable FMA" FORCE)
set(GGML_F16C OFF CACHE BOOL "Disable F16C" FORCE)

# For Android, ensure NEON is enabled for ARM64
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_NEON ON CACHE BOOL "Enable NEON" FORCE)
endif()

# FetchContent setup
include(FetchContent)

# 1. Fetch whisper.cpp
message(STATUS "Fetching whisper.cpp...")
FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.7.1
)
FetchContent_MakeAvailable(whispercpp)

# 2. Fetch llama.cpp
message(STATUS "Fetching llama.cpp...")
FetchContent_Declare(
    llamacpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        master
)
FetchContent_MakeAvailable(llamacpp)

# Define our JNI library
add_library(friday_native SHARED
    friday_jni.cpp
)

# Include directories for our JNI code
# FetchContent creates variables like <lowercaseName>_SOURCE_DIR
target_include_directories(friday_native PRIVATE
    ${whispercpp_SOURCE_DIR}
    ${whispercpp_SOURCE_DIR}/include
    ${llamacpp_SOURCE_DIR}
    ${llamacpp_SOURCE_DIR}/include
)

# Link native libraries.
# Targets in whisper.cpp and llama.cpp: whisper, llama, ggml
target_link_libraries(friday_native
    whisper
    llama
    log
    android
)
