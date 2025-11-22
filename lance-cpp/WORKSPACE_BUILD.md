# Building in Lance Monorepo vs Standalone

## The Problem

Lance C++ SDK can be built in two contexts:

1. **Within Lance Monorepo** - When `lance-cpp/` is inside the Lance repository
2. **Standalone** - When `lance-cpp/` is copied elsewhere

These require different dependency configurations in `rust/Cargo.toml`.

## Quick Fix

### If you're in the Lance monorepo:

The current `Cargo.toml` is already configured for monorepo builds (uses local paths). Just run:

```bash
./build.sh
```

### If you want standalone build:

Switch to Git dependencies:

```bash
./switch-deps.sh git
./build.sh
```

## Detailed Explanation

### Monorepo Build (Default)

When building inside Lance repository:

**Cargo.toml uses:**
```toml
[dependencies]
lance = { path = "../../rust/lance", features = ["substrait"] }
# ... other local paths
```

**Build:**
```bash
cd lance-cpp
./build.sh
```

### Standalone Build

When building outside Lance repository (e.g., after copying):

**Cargo.toml uses:**
```toml
[dependencies]
lance = { git = "https://github.com/lancedb/lance.git", branch = "main", features = ["substrait"] }
# ... other git dependencies
```

**Build:**
```bash
# Copy project
cp -r lance-cpp /tmp/standalone

# Switch to git dependencies
cd /tmp/standalone
./switch-deps.sh git

# Build
./build.sh
```

## Switching Dependencies

### Manual Method

Edit `rust/Cargo.toml` and change the `[dependencies]` section:

**For monorepo:**
```toml
lance = { path = "../../rust/lance", features = ["substrait"] }
```

**For standalone:**
```toml
lance = { git = "https://github.com/lancedb/lance.git", branch = "main", features = ["substrait"] }
```

### Automatic Method (Recommended)

Use the provided script:

```bash
# Switch to git dependencies (for standalone)
./switch-deps.sh git

# Switch to local dependencies (for monorepo)
./switch-deps.sh local
```

## Provided Files

The project includes three versions of Cargo.toml:

1. **Cargo.toml** - Active configuration (default: local paths)
2. **Cargo.toml.local** - Local path dependencies (monorepo)
3. **Cargo.toml.git** - Git dependencies (standalone)

The `switch-deps.sh` script copies the appropriate version.

## Recommended Workflow

### For Monorepo Development

```bash
cd lance-cpp

# Make sure using local dependencies
./switch-deps.sh local

# Build
./build.sh

# After changes, rebuild
./build.sh --skip-rust  # Fast C++ only rebuild
```

### For Distribution

```bash
# 1. Prepare standalone version
cd lance-cpp
./switch-deps.sh git

# 2. Test standalone build
cd /tmp
cp -r /path/to/lance-cpp .
cd lance-cpp
./build.sh

# 3. Package
tar czf lance-cpp-standalone.tar.gz lance-cpp/
```

### For End Users

When users download the standalone package:

```bash
# Extract
tar xzf lance-cpp-standalone.tar.gz
cd lance-cpp

# Build (already configured with git deps)
./build.sh
```

## Troubleshooting

### Error: "current package believes it's in a workspace"

**Symptom:**
```
error: current package believes it's in a workspace when it's not:
current:   /path/to/lance-cpp/rust/Cargo.toml
workspace: /path/to/lance/Cargo.toml
```

**Cause:** Using local path dependencies while Cargo.toml is in Lance workspace.

**Solution:**
This is actually correct! The error message is misleading. The build should still work. If not:

1. Make sure you're using local paths:
   ```bash
   ./switch-deps.sh local
   ```

2. Clean and rebuild:
   ```bash
   ./build.sh --clean
   ```

### Error: "no matching package named `lance` found"

**Cause:** Using git dependencies but the paths don't exist (or vice versa).

**Solution:**
```bash
# Check current dependencies
grep "^lance = " rust/Cargo.toml

# If wrong, switch
./switch-deps.sh local   # for monorepo
./switch-deps.sh git     # for standalone
```

### Build is very slow

**Cause:** First build with git dependencies downloads and compiles everything.

**Solution:** Be patient. First build takes 10-30 minutes. Subsequent builds are fast.

## Best Practices

### For Lance Repository Maintainers

1. Keep default `Cargo.toml` with **local paths**
2. Commit `Cargo.toml.git` for reference
3. Document in README that users should run `switch-deps.sh git` for standalone

### For Standalone Users

1. After copying/cloning, run `./switch-deps.sh git`
2. Or manually edit `rust/Cargo.toml` to use git dependencies
3. First build will be slow (downloads dependencies)

### For CI/CD

```yaml
# GitHub Actions example
- name: Setup dependencies
  run: |
    cd lance-cpp
    # Use git deps for clean build
    ./switch-deps.sh git
    
- name: Build
  run: |
    cd lance-cpp
    ./build.sh
```

## FAQ

**Q: Which should I use by default?**

A: 
- In monorepo: **local paths** (default)
- Standalone: **git dependencies**

**Q: Can I use a specific Lance version?**

A: Yes, edit `rust/Cargo.toml.git`:
```toml
lance = { git = "https://github.com/lancedb/lance.git", tag = "v0.35.0", features = ["substrait"] }
```

**Q: How do I know which mode I'm in?**

A: Check dependencies:
```bash
grep "^lance = " rust/Cargo.toml
```

Output:
- `path = ` → monorepo mode
- `git = ` → standalone mode

**Q: Can build.sh detect this automatically?**

A: Not easily, as it would require parsing Cargo.toml. The manual switch is safer and more explicit.

## Summary

| Context | Cargo.toml Config | Command |
|---------|-------------------|---------|
| **Lance Monorepo** | Local paths | `./build.sh` |
| **Standalone** | Git dependencies | `./switch-deps.sh git && ./build.sh` |
| **Switch Mode** | - | `./switch-deps.sh {local\|git}` |

For most users in the monorepo, just run `./build.sh` - it should work out of the box!

