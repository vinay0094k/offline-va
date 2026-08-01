cmake_minimum_required(VERSION 3.22)
project(whisper_jni CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.1
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whispercpp)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper log)
# Hide the statically-linked ggml/whisper symbols so they cannot clash with
# the copy of ggml inside llama_jni.so when both libraries are loaded.
target_link_options(whisper_jni PRIVATE -Wl,--exclude-libs,ALL)
