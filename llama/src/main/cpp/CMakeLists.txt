cmake_minimum_required(VERSION 3.22)
project(llama_jni CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llamacpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b10217
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llamacpp)

add_library(llama_jni SHARED llama_jni.cpp)
target_link_libraries(llama_jni PRIVATE llama log)
# Hide the statically-linked ggml/llama symbols so they cannot clash with
# the copy of ggml inside whisper_jni.so when both libraries are loaded.
target_link_options(llama_jni PRIVATE -Wl,--exclude-libs,ALL)
