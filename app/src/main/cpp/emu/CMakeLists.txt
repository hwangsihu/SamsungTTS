# Builds libsmtemu.so: dynarmic plus the ELF loader/JNI bridge in emu.cpp, which runs the
# 32-bit Samsung TTS engines on devices that cannot execute 32-bit code. Built out of band by
# tools/build_emulator.py (not by Gradle), which drops the result into jniLibs.
cmake_minimum_required(VERSION 3.20)
project(smtemu CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_TESTING OFF)
set(DYNARMIC_TESTS OFF CACHE BOOL "" FORCE)

add_subdirectory(${SMT_ROOT}/third_party/dynarmic dynarmic EXCLUDE_FROM_ALL)

add_library(smtemu SHARED emu.cpp)
target_link_libraries(smtemu PRIVATE dynarmic)
