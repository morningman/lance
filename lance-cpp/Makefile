# SPDX-License-Identifier: Apache-2.0
# Makefile for Lance C++ SDK
# This is a convenience wrapper around build.sh and CMake

.PHONY: all build clean release debug examples help install test

# Default target
all: release

# Show help
help:
	@echo "Lance C++ SDK - Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  make           - Build release version (default)"
	@echo "  make release   - Build release version"
	@echo "  make debug     - Build debug version"
	@echo "  make clean     - Clean build artifacts"
	@echo "  make rebuild   - Clean and build"
	@echo "  make examples  - Build examples only"
	@echo "  make test      - Run tests (if available)"
	@echo "  make install   - Install libraries and headers"
	@echo "  make help      - Show this help"
	@echo ""
	@echo "Environment variables:"
	@echo "  JOBS=N         - Number of parallel jobs (default: auto)"
	@echo "  CXX=compiler   - C++ compiler to use"
	@echo ""
	@echo "Examples:"
	@echo "  make                    # Standard build"
	@echo "  make JOBS=16            # Build with 16 parallel jobs"
	@echo "  make clean release      # Clean build"
	@echo "  make CXX=clang++ debug  # Debug build with clang++"

# Build release version
release build:
	@echo "Building release version..."
	@./build.sh $(if $(JOBS),-j $(JOBS),)

# Build debug version
debug:
	@echo "Building debug version..."
	@./build.sh --debug $(if $(JOBS),-j $(JOBS),)

# Clean build artifacts
clean:
	@echo "Cleaning build artifacts..."
	@rm -rf build/
	@cd rust && cargo clean
	@echo "Clean complete"

# Clean and rebuild
rebuild: clean release

# Build examples only (skip Rust if already built)
examples:
	@echo "Building examples..."
	@./build.sh --skip-rust $(if $(JOBS),-j $(JOBS),)

# Install (requires CMake build)
install:
	@if [ ! -d "build" ]; then \
		echo "Error: Build directory not found. Run 'make' first."; \
		exit 1; \
	fi
	@echo "Installing..."
	@cd build && sudo cmake --install .

# Run tests (placeholder)
test:
	@echo "Running tests..."
	@if [ -f "build/examples/basic_read" ]; then \
		echo "✓ basic_read exists"; \
	else \
		echo "✗ basic_read not found"; \
	fi
	@if [ -f "build/examples/parallel_read" ]; then \
		echo "✓ parallel_read exists"; \
	else \
		echo "✗ parallel_read not found"; \
	fi
	@if [ -f "build/liblance_cpp.a" ]; then \
		echo "✓ liblance_cpp.a exists"; \
	else \
		echo "✗ liblance_cpp.a not found"; \
	fi

