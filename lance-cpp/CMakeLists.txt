# SPDX-License-Identifier: Apache-2.0

cmake_minimum_required(VERSION 3.20)
project(lance-cpp VERSION 0.1.0 LANGUAGES CXX)

# C++20 standard
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Build options
option(BUILD_EXAMPLES "Build example programs" ON)
option(BUILD_TESTS "Build tests" OFF)

# Find required packages
find_package(Arrow REQUIRED)

# Determine Rust target directory
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(RUST_BUILD_TYPE "debug")
    set(CARGO_CMD cargo build)
else()
    set(RUST_BUILD_TYPE "release")
    set(CARGO_CMD cargo build --release)
endif()

set(RUST_TARGET_DIR "${CMAKE_CURRENT_SOURCE_DIR}/rust/target/${RUST_BUILD_TYPE}")

# Platform-specific library names
if(APPLE)
    set(RUST_LIB_NAME "liblance_cpp_ffi.a")
elseif(UNIX)
    set(RUST_LIB_NAME "liblance_cpp_ffi.a")
elseif(WIN32)
    set(RUST_LIB_NAME "lance_cpp_ffi.lib")
endif()

set(RUST_LIB_PATH "${RUST_TARGET_DIR}/${RUST_LIB_NAME}")

# Custom target to build Rust library
add_custom_target(lance_rust_build
    COMMAND ${CARGO_CMD}
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/rust
    COMMENT "Building Rust FFI library..."
    BYPRODUCTS ${RUST_LIB_PATH}
)

# Imported library for Rust FFI
add_library(lance_rust_ffi STATIC IMPORTED GLOBAL)
set_target_properties(lance_rust_ffi PROPERTIES
    IMPORTED_LOCATION ${RUST_LIB_PATH}
)
add_dependencies(lance_rust_ffi lance_rust_build)

# Platform-specific system libraries
if(APPLE)
    set(SYSTEM_LIBS
        "-framework Security"
        "-framework CoreFoundation"
        "-framework SystemConfiguration"
        resolv
    )
elseif(UNIX)
    set(SYSTEM_LIBS
        dl
        m
        pthread
    )
elseif(WIN32)
    set(SYSTEM_LIBS
        ws2_32
        userenv
        bcrypt
        ntdll
    )
endif()

# Lance C++ wrapper library
add_library(lance_cpp STATIC
    src/error.cpp
    src/dataset.cpp
    src/scanner.cpp
    src/lance.cpp
)

target_include_directories(lance_cpp
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/rust/include/lance/ffi
)

target_link_libraries(lance_cpp
    PUBLIC
        Arrow::arrow_shared
    PRIVATE
        lance_rust_ffi
        ${SYSTEM_LIBS}
)

# Compile options
target_compile_options(lance_cpp PRIVATE
    $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic>
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
)

# Build examples
if(BUILD_EXAMPLES)
    add_subdirectory(examples)
endif()

# Installation
install(TARGETS lance_cpp
    ARCHIVE DESTINATION lib
    LIBRARY DESTINATION lib
)

install(DIRECTORY include/lance
    DESTINATION include
    FILES_MATCHING PATTERN "*.hpp"
)

# Print configuration summary
message(STATUS "Lance C++ SDK Configuration:")
message(STATUS "  Build type: ${CMAKE_BUILD_TYPE}")
message(STATUS "  C++ standard: C++${CMAKE_CXX_STANDARD}")
message(STATUS "  Rust library: ${RUST_LIB_PATH}")
message(STATUS "  Arrow version: ${Arrow_VERSION}")
message(STATUS "  Build examples: ${BUILD_EXAMPLES}")

