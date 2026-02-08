# Miniforge Deployment

## Two-Package Architecture

Miniforge uses a two-package deployment model to optimize for both speed and features:

### 1. `miniforge` (Primary CLI)

**Runtime**: Babashka
**Purpose**: Fast, lightweight CLI for core workflows
**Installation**: `brew install miniforge`

**Features**:

- Workflow execution
- PR operations
- Fleet management
- Web dashboard
- Configuration management

**Startup time**: <100ms (Babashka instant startup)
**Binary size**: ~50MB (includes Babashka)

### 2. `miniforge-tui` (Terminal UI)

**Runtime**: jlink-bundled JVM
**Purpose**: Rich terminal UI with 5 N5 views
**Installation**: `brew install miniforge-tui`

**Features**:

- Real-time workflow visualization
- Interactive terminal UI (Lanterna)
- 5 N5 views: workflow list, detail, evidence, artifacts, DAG kanban
- vim-style navigation

**Startup time**: ~200ms (jlink-optimized JVM)
**Binary size**: ~80MB (includes minimal JVM runtime)

## Why Two Packages?

### Speed First Principle

The core `miniforge` CLI uses Babashka for instant startup (<100ms). This is critical for:

- CI/CD pipelines that call miniforge repeatedly
- Developer workflow scripts
- Homebrew formula simplicity

### Rich TUI Requires JVM

The terminal UI uses Lanterna, which requires JVM classes not available in Babashka:

- Java AWT/Swing for terminal handling
- `com.googlecode.lanterna.*` classes
- Complex terminal state management

### jlink Optimization

Rather than require users to install Java separately, `miniforge-tui` bundles a minimal JVM:

```bash
# Created via jlink
jlink \
  --add-modules java.base,java.desktop \
  --strip-debug \
  --no-man-pages \
  --no-header-files \
  --compress=2 \
  --output miniforge-tui-jvm
```

**Benefits**:

- No Java installation required
- Minimal runtime (~40MB vs ~200MB full JDK)
- Optimized module set (only what's needed)
- Fast startup with AppCDS

## Usage

### Primary CLI (Babashka)

```bash
brew install miniforge

# Core commands (fast startup)
miniforge run spec.edn
miniforge pr review https://...
miniforge fleet web
```

### Terminal UI (jlink JVM)

```bash
brew install miniforge-tui

# Launch terminal UI
miniforge fleet tui

# Alternative: direct invocation
miniforge-tui
```

## Homebrew Formulas

### miniforge.rb

```ruby
class Miniforge < Formula
  desc "AI-powered software development workflows"
  homepage "https://miniforge.ai"
  url "https://github.com/miniforge-ai/miniforge/releases/download/v1.0.0/miniforge-1.0.0.jar"

  depends_on "babashka"

  def install
    libexec.install "miniforge.jar"
    bin.write_jar_script libexec/"miniforge.jar", "miniforge"
  end
end
```

### miniforge-tui.rb

```ruby
class MiniforgeTui < Formula
  desc "Terminal UI for miniforge workflows"
  homepage "https://miniforge.ai"
  url "https://github.com/miniforge-ai/miniforge/releases/download/v1.0.0/miniforge-tui-1.0.0.tar.gz"

  def install
    # jlink-bundled JVM included in tarball
    libexec.install Dir["*"]
    bin.install_symlink libexec/"bin/miniforge-tui"
  end
end
```

## Build Process

### Primary CLI

```bash
# Build Babashka-compatible uberjar
bb build:cli

# Creates: dist/miniforge.jar (Babashka-compatible)
```

### TUI Package

```bash
# Build with jlink bundling
bb build:tui

# Steps:
# 1. Build JVM uberjar (includes Lanterna)
# 2. Create jlink runtime with minimal modules
# 3. Package with launch script
# 4. Creates: dist/miniforge-tui-1.0.0.tar.gz
```

## Testing GraalVM Compatibility

We maintain GraalVM/Babashka compatibility tests:

```bash
bb test:graalvm
```

This ensures the primary CLI never regresses to requiring JVM.

## Future Enhancements

- **Native Image**: Explore GraalVM Native Image for TUI (if Lanterna supports it)
- **Web TUI**: Browser-based TUI that works with primary CLI (no JVM needed)
- **Electron App**: Desktop app with embedded Chromium (different trade-offs)
