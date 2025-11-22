#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Build script for Lance C++ SDK

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
BUILD_TYPE="Release"
BUILD_DIR="build"
NUM_JOBS=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
SKIP_RUST=false
CLEAN_BUILD=false
BUILD_EXAMPLES=true
VERBOSE=false

# Script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

# Print colored message
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_header() {
    echo -e "\n${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}\n"
}

# Usage information
usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Build Lance C++ SDK and generate static library (.a) and examples.

OPTIONS:
    -h, --help              Show this help message
    -d, --debug             Build in Debug mode (default: Release)
    -c, --clean             Clean build directory before building
    -j, --jobs NUM          Number of parallel jobs (default: $NUM_JOBS)
    --skip-rust             Skip Rust build (use existing library)
    --no-examples           Don't build example programs
    -v, --verbose           Verbose output
    --build-dir DIR         Custom build directory (default: build)

EXAMPLES:
    $0                      # Build with default settings
    $0 --clean              # Clean build
    $0 --debug -j 8         # Debug build with 8 parallel jobs
    $0 --skip-rust          # Skip Rust, only build C++

ENVIRONMENT VARIABLES:
    CXX                     C++ compiler (default: g++ or clang++)
    CMAKE                   CMake executable (default: cmake)
    CARGO                   Cargo executable (default: cargo)

EOF
    exit 0
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            usage
            ;;
        -d|--debug)
            BUILD_TYPE="Debug"
            shift
            ;;
        -c|--clean)
            CLEAN_BUILD=true
            shift
            ;;
        -j|--jobs)
            NUM_JOBS="$2"
            shift 2
            ;;
        --skip-rust)
            SKIP_RUST=true
            shift
            ;;
        --no-examples)
            BUILD_EXAMPLES=false
            shift
            ;;
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        --build-dir)
            BUILD_DIR="$2"
            shift 2
            ;;
        *)
            print_error "Unknown option: $1"
            usage
            ;;
    esac
done

# Check prerequisites
print_header "Checking Prerequisites"

# Check for required tools
check_command() {
    if ! command -v $1 &> /dev/null; then
        print_error "$1 is not installed or not in PATH"
        return 1
    else
        print_info "$1: $(command -v $1)"
        return 0
    fi
}

MISSING_DEPS=false

if ! check_command cmake; then MISSING_DEPS=true; fi
if ! check_command cargo; then MISSING_DEPS=true; fi
if ! check_command rustc; then MISSING_DEPS=true; fi

# Check for C++ compiler
if [ -z "$CXX" ]; then
    if command -v g++ &> /dev/null; then
        CXX=g++
    elif command -v clang++ &> /dev/null; then
        CXX=clang++
    else
        print_error "No C++ compiler found. Please install g++ or clang++"
        MISSING_DEPS=true
    fi
fi

if [ "$MISSING_DEPS" = true ]; then
    print_error "Missing required dependencies. Please install them first."
    echo ""
    echo "Installation instructions:"
    echo "  Ubuntu/Debian: sudo apt install -y cmake g++ build-essential"
    echo "  macOS:         brew install cmake llvm"
    echo "  Rust:          curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"
    exit 1
fi

print_success "All prerequisites satisfied"

# Check for Arrow
print_info "Checking for Apache Arrow..."
if pkg-config --exists arrow 2>/dev/null; then
    ARROW_VERSION=$(pkg-config --modversion arrow)
    print_info "Found Arrow version: $ARROW_VERSION"
elif [ -f "/usr/local/lib/cmake/Arrow/ArrowConfig.cmake" ]; then
    print_info "Found Arrow CMake config"
elif [ -f "/opt/homebrew/lib/cmake/Arrow/ArrowConfig.cmake" ]; then
    print_info "Found Arrow CMake config (Homebrew)"
else
    print_warning "Arrow not found in standard locations"
    print_warning "Build may fail if Arrow is not installed"
    echo ""
    echo "To install Arrow:"
    echo "  Ubuntu/Debian: See instructions in BUILD_GUIDE.md"
    echo "  macOS:         brew install apache-arrow"
fi

# Check if we're in a workspace
print_info "Checking build context..."
if [ -f "../Cargo.toml" ] && grep -q "\[workspace\]" "../Cargo.toml" 2>/dev/null; then
    print_info "Detected: Building within Lance monorepo"
    WORKSPACE_MODE=true
else
    print_info "Detected: Standalone build"
    WORKSPACE_MODE=false
fi

# Verify Cargo.toml configuration
cd rust
if grep -q 'git = "https://github.com/lancedb/lance.git"' Cargo.toml; then
    USING_GIT_DEPS=true
else
    USING_GIT_DEPS=false
fi
cd ..

if [ "$WORKSPACE_MODE" = true ] && [ "$USING_GIT_DEPS" = true ]; then
    print_warning "You are in Lance monorepo but using git dependencies"
    print_warning "This may cause issues. Consider running:"
    print_warning "  ./switch-deps.sh local"
    echo ""
elif [ "$WORKSPACE_MODE" = false ] && [ "$USING_GIT_DEPS" = false ]; then
    print_warning "You are building standalone but using local path dependencies"
    print_warning "This may fail if Lance repo is not at ../../rust/"
    print_warning "Consider running:"
    print_warning "  ./switch-deps.sh git"
    echo ""
fi

# Print build configuration
print_header "Build Configuration"
echo "Build Type:        $BUILD_TYPE"
echo "Build Directory:   $BUILD_DIR"
echo "Parallel Jobs:     $NUM_JOBS"
echo "C++ Compiler:      $CXX"
echo "Skip Rust Build:   $SKIP_RUST"
echo "Build Examples:    $BUILD_EXAMPLES"
echo "Clean Build:       $CLEAN_BUILD"
echo "Workspace Mode:    $WORKSPACE_MODE"
echo "Using Git Deps:    $USING_GIT_DEPS"

# Clean build directory if requested
if [ "$CLEAN_BUILD" = true ]; then
    print_header "Cleaning Build Directory"
    if [ -d "$BUILD_DIR" ]; then
        print_info "Removing $BUILD_DIR..."
        rm -rf "$BUILD_DIR"
        print_success "Build directory cleaned"
    else
        print_info "Build directory does not exist, skipping clean"
    fi
    
    # Also clean Rust build
    if [ -d "rust/target" ]; then
        print_info "Cleaning Rust build artifacts..."
        cd rust
        cargo clean
        cd ..
        print_success "Rust artifacts cleaned"
    fi
fi

# Build Rust FFI library
if [ "$SKIP_RUST" = false ]; then
    print_header "Building Rust FFI Library"
    
    cd rust
    
    # Check if Cargo.lock exists, if not initialize
    if [ ! -f "Cargo.lock" ]; then
        print_info "Generating Cargo.lock..."
        cargo generate-lockfile
    fi
    
    # Build Rust library
    print_info "Building Rust library (this may take a while on first build)..."
    
    if [ "$VERBOSE" = true ]; then
        CARGO_CMD="cargo build --release --verbose"
    else
        CARGO_CMD="cargo build --release"
    fi
    
    if [ "$BUILD_TYPE" = "Debug" ]; then
        CARGO_CMD="cargo build"
    fi
    
    print_info "Running: $CARGO_CMD"
    
    if $CARGO_CMD; then
        print_success "Rust FFI library built successfully"
    else
        print_error "Rust build failed"
        exit 1
    fi
    
    # Check output
    if [ "$BUILD_TYPE" = "Release" ]; then
        RUST_LIB="target/release/liblance_cpp_ffi.a"
    else
        RUST_LIB="target/debug/liblance_cpp_ffi.a"
    fi
    
    if [ -f "$RUST_LIB" ]; then
        LIB_SIZE=$(du -h "$RUST_LIB" | cut -f1)
        print_info "Rust library: $RUST_LIB (${LIB_SIZE})"
    else
        print_error "Rust library not found at $RUST_LIB"
        exit 1
    fi
    
    # Check if C header was generated
    FFI_HEADER="include/lance/ffi/lance_ffi.h"
    if [ -f "$FFI_HEADER" ]; then
        print_info "FFI header generated: $FFI_HEADER"
    else
        print_warning "FFI header not found, will be generated by CMake"
    fi
    
    cd ..
else
    print_warning "Skipping Rust build as requested"
fi

# Configure CMake
print_header "Configuring CMake"

mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

CMAKE_ARGS=(
    "-DCMAKE_BUILD_TYPE=$BUILD_TYPE"
    "-DCMAKE_CXX_COMPILER=$CXX"
)

if [ "$BUILD_EXAMPLES" = false ]; then
    CMAKE_ARGS+=("-DBUILD_EXAMPLES=OFF")
fi

if [ "$VERBOSE" = true ]; then
    CMAKE_ARGS+=("-DCMAKE_VERBOSE_MAKEFILE=ON")
fi

print_info "Running: cmake .. ${CMAKE_ARGS[@]}"

if cmake .. "${CMAKE_ARGS[@]}"; then
    print_success "CMake configuration successful"
else
    print_error "CMake configuration failed"
    exit 1
fi

# Build C++ library
print_header "Building C++ Library"

CMAKE_BUILD_ARGS=(
    "--build" "."
    "--config" "$BUILD_TYPE"
    "-j" "$NUM_JOBS"
)

if [ "$VERBOSE" = true ]; then
    CMAKE_BUILD_ARGS+=("--verbose")
fi

print_info "Running: cmake ${CMAKE_BUILD_ARGS[@]}"

if cmake "${CMAKE_BUILD_ARGS[@]}"; then
    print_success "C++ library built successfully"
else
    print_error "C++ build failed"
    exit 1
fi

cd ..

# Verify outputs
print_header "Build Artifacts"

ARTIFACTS_OK=true

# Check C++ static library
CPP_LIB="$BUILD_DIR/liblance_cpp.a"
if [ -f "$CPP_LIB" ]; then
    LIB_SIZE=$(du -h "$CPP_LIB" | cut -f1)
    print_success "✓ C++ library: $CPP_LIB (${LIB_SIZE})"
else
    print_error "✗ C++ library not found"
    ARTIFACTS_OK=false
fi

# Check Rust FFI library
if [ "$BUILD_TYPE" = "Release" ]; then
    RUST_LIB="rust/target/release/liblance_cpp_ffi.a"
else
    RUST_LIB="rust/target/debug/liblance_cpp_ffi.a"
fi

if [ -f "$RUST_LIB" ]; then
    LIB_SIZE=$(du -h "$RUST_LIB" | cut -f1)
    print_success "✓ Rust FFI library: $RUST_LIB (${LIB_SIZE})"
else
    print_error "✗ Rust FFI library not found"
    ARTIFACTS_OK=false
fi

# Check examples
if [ "$BUILD_EXAMPLES" = true ]; then
    BASIC_READ="$BUILD_DIR/examples/basic_read"
    PARALLEL_READ="$BUILD_DIR/examples/parallel_read"
    
    if [ -f "$BASIC_READ" ]; then
        EXE_SIZE=$(du -h "$BASIC_READ" | cut -f1)
        print_success "✓ Example: basic_read (${EXE_SIZE})"
    else
        print_error "✗ basic_read example not found"
        ARTIFACTS_OK=false
    fi
    
    if [ -f "$PARALLEL_READ" ]; then
        EXE_SIZE=$(du -h "$PARALLEL_READ" | cut -f1)
        print_success "✓ Example: parallel_read (${EXE_SIZE})"
    else
        print_error "✗ parallel_read example not found"
        ARTIFACTS_OK=false
    fi
fi

# Summary
print_header "Build Summary"

if [ "$ARTIFACTS_OK" = true ]; then
    print_success "Build completed successfully!"
    echo ""
    echo "Outputs:"
    echo "  Static libraries:"
    echo "    - $CPP_LIB"
    echo "    - $RUST_LIB"
    if [ "$BUILD_EXAMPLES" = true ]; then
        echo "  Examples:"
        echo "    - $BUILD_DIR/examples/basic_read"
        echo "    - $BUILD_DIR/examples/parallel_read"
    fi
    echo ""
    echo "Usage:"
    if [ "$BUILD_EXAMPLES" = true ]; then
        echo "  Run examples:"
        echo "    $BUILD_DIR/examples/basic_read /path/to/dataset"
        echo "    $BUILD_DIR/examples/parallel_read /path/to/dataset 4"
        echo ""
    fi
    echo "  Link in your project:"
    echo "    g++ -std=c++20 myapp.cpp -I./include -L./$BUILD_DIR -llance_cpp -L./rust/target/release -llance_cpp_ffi -larrow -lpthread -ldl -lm -o myapp"
    echo ""
    echo "For more information, see README.md and BUILD_GUIDE.md"
else
    print_error "Build completed with errors"
    exit 1
fi

