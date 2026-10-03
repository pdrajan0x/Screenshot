cmake_minimum_required(VERSION 3.22.1)
project(dotllm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp" CACHE PATH "llama.cpp checkout")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_DIR}; run tools/fetch_llama.sh")
endif()

# Shared ggml with one CPU backend per ARM feature level (armv8.0 … armv9.2); the best one for the
# phone is loaded at runtime, so old budget chips and new flagships both get the fastest kernels.
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
# Image understanding (photo descriptions): the mtmd library alone, without the tools.
set(LLAMA_BUILD_MTMD      ON  CACHE BOOL "" FORCE)
set(MTMD_VIDEO            OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_DIR} llama-build)

add_library(dotllm SHARED dot_llm.cpp)
target_link_libraries(dotllm PRIVATE llama ggml mtmd)
if(ANDROID)
    target_link_libraries(dotllm PRIVATE log)
endif()
