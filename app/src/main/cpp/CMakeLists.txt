cmake_minimum_required(VERSION 3.22.1)
project(friday_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Configure GGML / llama.cpp / whisper.cpp flags for Android before fetching
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan" FORCE)
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
    add_compile_options(-march=armv8.2-a+dotprod)
endif()

# Speed up compile by disabling tests, examples, and server builds
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "Disable Whisper tests" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "Disable Whisper examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Disable Llama tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Disable Llama examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Disable Llama server" FORCE)

# FetchContent setup
include(FetchContent)

# 1. Fetch llama.cpp first to ensure its newer ggml target structure is used first
message(STATUS "Fetching llama.cpp...")
FetchContent_Declare(
    llamacpp_new
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        master
)
FetchContent_MakeAvailable(llamacpp_new)

# 2. Fetch whisper.cpp second
message(STATUS "Fetching whisper.cpp...")
FetchContent_Declare(
    whispercpp_new
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        master
)
FetchContent_MakeAvailable(whispercpp_new)

# Define our JNI library
add_library(friday_native SHARED
    friday_jni.cpp
)

# Include directories for our JNI code
target_include_directories(friday_native PRIVATE
    ${whispercpp_new_SOURCE_DIR}
    ${whispercpp_new_SOURCE_DIR}/include
    ${llamacpp_new_SOURCE_DIR}
    ${llamacpp_new_SOURCE_DIR}/include
    ${llamacpp_new_SOURCE_DIR}/ggml/include
)

# Link native libraries.
target_link_libraries(friday_native
    whisper
    llama
    log
    android
)
