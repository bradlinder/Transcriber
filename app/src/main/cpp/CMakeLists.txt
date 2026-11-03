cmake_minimum_required(VERSION 3.22.1)

project("whisper_android")

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

FetchContent_Declare(
    whisper_upstream
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG        v1.6.0
)

FetchContent_MakeAvailable(whisper_upstream)

add_library(
    whisper_jni
    SHARED
    whisper_jni.cpp
)

target_include_directories(
    whisper_jni
    PRIVATE
    ${whisper_upstream_SOURCE_DIR}
    ${whisper_upstream_SOURCE_DIR}/ggml/include
)

target_link_libraries(
    whisper_jni
    whisper
    android
    log
)
