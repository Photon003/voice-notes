cmake_minimum_required(VERSION 3.22)
project(voicenotes C CXX)

# Always optimise the speech engine, it is far too slow otherwise.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
# Modern ARM phones (2018+): half-precision maths + dot product.
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)

# whisper.cpp source is downloaded here by the GitHub build.
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp whisper-build)

add_library(voicenotes SHARED native-lib.cpp)
find_library(log-lib log)
target_link_libraries(voicenotes PRIVATE whisper android ${log-lib})
