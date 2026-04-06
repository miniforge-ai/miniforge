<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

<img src="logo.png" width="30%" alt="Miniforge" id="logo">

# Miniforge

Autonomous software factory — a self-directing SDLC platform built on
multi-agent cognition, powered by [MiniForge Core](#miniforge-core).

## Products

This repository is a Polylith monorepo that houses three layers:

| Layer | Description |
|-------|-------------|
| **MiniForge Core** | Governed workflow engine — DAG executor, phase registry, policy SDK, shared CLI base. Designed to be embedded in any product that needs a governed workflow runtime. |
| **Miniforge** | Autonomous software factory — SDLC workflows, fleet management, PR lifecycle, multi-agent code generation. |
| **Data Foundry** | ETL product — data extraction, transformation, and loading workflows built on the same governed runtime. |

## Installation

### For End Users

Install Miniforge via Homebrew:

```bash
# Add the miniforge tap
brew tap miniforge-ai/tap

# Install miniforge
brew install miniforge

# Verify installation
miniforge version
```

Or download directly from [GitHub Releases](https://github.com/miniforge-ai/miniforge/releases).

### CLI Commands

```bash
miniforge status        # Show system status
miniforge workflows     # List all workflows
miniforge workflow <id> # Show workflow detail
miniforge meta          # Show meta-loop status
miniforge version       # Show version information
miniforge help          # Show help message
```

Note: Full workflow and meta-loop features will be available as components are integrated.

### For Contributors

If you want to contribute to Miniforge development, follow the Quick Start guide below.

## Quick Start (Development)

### Prerequisites

- [Homebrew](https://brew.sh/) — Package manager (macOS/Linux)
- [Babashka](https://github.com/babashka/babashka#installation) (bb) — Clojure scripting

```bash
# Install Babashka if not already installed
brew install babashka/brew/babashka
```

### Bootstrap

```bash
# Clone the repository
git clone git@github.com:miniforge-ai/miniforge.git
cd miniforge

# Bootstrap: install all dependencies + configure environment
bb bootstrap
```

The `bb bootstrap` command will:

- Install all dependencies via Homebrew:
  - Java (Temurin 21)
  - Clojure CLI
  - clj-kondo (linter)
  - markdownlint-cli
  - Polylith CLI
- Configure git to use project hooks (`.githooks/`)
- Verify your git email is set to `@miniforge.ai`

### Git Email

All commits must use a `@miniforge.ai` email address. Configure it:

```bash
git config user.email 'yourname@miniforge.ai'
```

For automatic configuration across all miniforge repos, add to `~/.gitconfig`:

```ini
[includeIf "gitdir:~/path/to/miniforge-repos/"]
    path = ~/path/to/miniforge-repos/.gitconfig
```

Then create `~/path/to/miniforge-repos/.gitconfig`:

```ini
[user]
    email = yourname@miniforge.ai
```

## Development

### Local Development (Without Homebrew)

For rapid development iteration, build and install locally:

```bash
# Build and install in one step (cleans old build automatically)
bb install:local

# Now you can run miniforge locally
mf version
mf help
mf fleet web
```

The `bb install:local` task:

1. Removes old build (`rm -f dist/miniforge.jar`)
2. Builds fresh uberjar (`bb build:cli`)
3. Installs to `~/.local/bin/mf`

**Note:** Make sure `~/.local/bin` is in your PATH:

```bash
# Add to ~/.zshrc or ~/.bashrc
export PATH="$HOME/.local/bin:$PATH"
```

### Available Tasks

Run `bb` to see all available tasks:

```bash
bb                  # List all tasks

# Bootstrap & Setup
bb bootstrap        # Full bootstrap: install deps + configure env
bb setup            # Alias for bootstrap
bb install:deps     # Install all dependencies
bb upgrade:deps     # Upgrade all dependencies

# Linting & Formatting
bb lint:clj         # Lint staged Clojure files
bb lint:clj:all     # Lint all Clojure files
bb fmt:md           # Format staged Markdown files

# Testing
bb test             # Run unit tests
bb test:all         # Run all tests including integration

# Building
bb build:cli        # Build Miniforge CLI as uberscript
bb build:jar <proj> # Build JVM uberjar for a project
bb build:all        # Build all changed projects
bb clean            # Clean build artifacts

# Git Hooks
bb pre-commit       # Run all pre-commit checks manually
bb hooks:uninstall  # Reset git hooks to default
```

### Project Structure (Polylith)

```text
miniforge/
├── bases/          # Entry points (CLI, servers)
├── components/     # Reusable building blocks
│   ├── workflow/               # MiniForge Core — shared workflow runtime
│   ├── workflow-software-factory/  # Miniforge — SDLC workflows
│   ├── workflow-financial-etl/     # Data Foundry — ETL workflows (financial is one family)
│   ├── schema/     # Malli schemas for domain types
│   └── logging/    # Structured EDN logging
├── projects/       # Deployable artifacts
│   ├── miniforge/      # Miniforge (software factory) project
│   ├── miniforge-core/ # MiniForge Core (engine-only) project
│   └── miniforge-tui/  # Terminal UI project
├── development/    # Dev-time utilities
└── docs/
    └── specs/      # Product specifications
```

## MiniForge Core

MiniForge Core is the shared governed workflow engine extracted from this
repository. It provides:

- DAG executor with profile seams
- Generic workflow runtime, triggers, and publication
- Generic phase registry
- Policy-pack SDK surface
- Shared CLI base with app/config/message seams

The `projects/miniforge-core/` project composes only the kernel components,
producing a standalone artifact suitable for embedding in other products
(Data Foundry, Fleet, or any future governed workflow application).

## Documentation

- [Polylith Documentation](https://polylith.gitbook.io/polylith)
- [Product Specs](docs/specs/) — Detailed specifications

## Architecture

See [docs/specs/architecture.spec](docs/specs/architecture.spec) for the full architecture overview.

## License

Miniforge is licensed under the [Apache License 2.0](LICENSE).

Title: Miniforge.ai
Subtitle: An agentic SDLC / fleet-control platform
Author: Christopher Lester
Line: Founder, Miniforge.ai (project)
Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
