<img src="logo.png" width="30%" alt="miniforge" id="logo">

# miniforge

Autonomous SDLC platform — a self-directing software factory built on multi-agent cognition.

## Installation

### For End Users

Install miniforge via Homebrew:

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

If you want to contribute to miniforge development, follow the Quick Start guide below.

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
bb build:cli        # Build miniforge CLI as uberscript
bb build:jar <proj> # Build JVM uberjar for a project
bb build:all        # Build all changed projects
bb clean            # Clean build artifacts

# Git Hooks
bb pre-commit       # Run all pre-commit checks manually
bb hooks:uninstall  # Reset git hooks to default
```

### Project Structure (Polylith)

```
miniforge/
├── bases/          # Entry points (CLI, servers)
├── components/     # Reusable building blocks
│   ├── schema/     # Malli schemas for domain types
│   └── logging/    # Structured EDN logging
├── projects/       # Deployable artifacts
├── development/    # Dev-time utilities
└── docs/
    └── specs/      # Product specifications
```

## Documentation

- [Polylith Documentation](https://polylith.gitbook.io/polylith)
- [Product Specs](docs/specs/) — Detailed specifications for miniforge features

## Architecture

See [docs/specs/architecture.spec](docs/specs/architecture.spec) for the full architecture overview.

## License

miniforge is licensed under the [Apache License 2.0](LICENSE).

Copyright 2025 miniforge.ai
