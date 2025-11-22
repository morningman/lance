#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Switch between git and local dependencies

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR/rust"

case "$1" in
    git)
        echo "Switching to Git dependencies..."
        if [ -f "Cargo.toml.git" ]; then
            cp Cargo.toml.git Cargo.toml
            echo "✓ Switched to Git dependencies"
        else
            echo "✗ Cargo.toml.git not found"
            exit 1
        fi
        ;;
    local)
        echo "Switching to local dependencies..."
        if [ -f "Cargo.toml.local" ]; then
            cp Cargo.toml.local Cargo.toml
            echo "✓ Switched to local dependencies"
        else
            echo "✗ Cargo.toml.local not found"
            exit 1
        fi
        ;;
    *)
        echo "Usage: $0 {git|local}"
        echo ""
        echo "  git   - Use git dependencies (for standalone builds)"
        echo "  local - Use local path dependencies (for monorepo development)"
        exit 1
        ;;
esac

echo ""
echo "Current dependencies:"
grep -A 1 "lance = {" Cargo.toml | head -2

