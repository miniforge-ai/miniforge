<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Contributing to Miniforge

Thank you for contributing to Miniforge! This guide will help you get started.

## Quick Start

1. **Fork and Clone**

   ```bash
   git clone https://github.com/YOUR-USERNAME/miniforge.git
   cd miniforge
   ```

2. **Install Dependencies**

   ```bash
   # macOS
   brew install babashka/brew/babashka

   # Linux (static binary)
   curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
   chmod +x install && ./install --static

   # markdownlint (all platforms)
   npm install -g markdownlint-cli
   ```

   ```powershell
   # Windows (PowerShell — native, in beta)
   # If you hit an execution-policy error:
   #   Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
   Invoke-Expression (New-Object System.Net.WebClient).DownloadString('https://get.scoop.sh')
   scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
   scoop bucket add extras
   scoop install babashka clojure
   npm install -g markdownlint-cli
   ```

   > Native Windows dev is in beta. `bb bootstrap` and the bash demo
   > script still assume a Unix shell — if you hit them, use **WSL2** or
   > **Git Bash**. See [Platform Support](docs/platform-support.md).

3. **Run Tests**

   ```bash
   bb test  # Run all tests
   ```

4. **Create a Branch**

   ```bash
   git checkout -b feature/your-feature-name
   # or
   git checkout -b fix/issue-description
   ```

5. **Make Changes and Commit**

   ```bash
   # Make your changes
   git add .
   git commit -m "feat: your change description"
   # Pre-commit hooks will run automatically
   ```

6. **Push and Create PR**

   ```bash
   git push -u origin feature/your-feature-name
   gh pr create  # or create via GitHub web UI
   ```

## Development Guidelines

**IMPORTANT:** Please read our [Development Guidelines](docs/development-guidelines.md) before contributing.

### TL;DR - Golden Rules

1. **Write Small Functions** - Target 5-15 lines, max 30 lines
   - Build complexity through composition, not giant functions
   - Each function should have a single responsibility

2. **Never Skip Pre-commit Hooks** - Fix warnings, don't bypass them
   - Pre-commit hooks catch issues before CI
   - `git commit --no-verify` is not allowed

3. **Fix Broken Tests** - Even if "not your fault"
   - Create separate PR from main to fix unrelated test failures
   - Don't mix test fixes with feature work

4. **Never Comment Out Tests** - Understand intent, then fix or remove
   - Read the test to understand what it validates
   - If behavior still needed: fix the test
   - If code removed: delete the test (with explanation)
   - If unsure: ask in PR review

See [Development Guidelines](docs/development-guidelines.md) for detailed examples and rationale.

## Architecture

This repo is a Polylith monorepo with three product layers:

- **MiniForge Core** — governed workflow engine (shared kernel)
- **Miniforge** — autonomous software factory (SDLC product)
- **Data Foundry** — ETL product (data extraction, transformation, and loading)

```text
miniforge/
├── components/          # Reusable components
│   ├── workflow/       # MiniForge Core — shared workflow runtime
│   ├── workflow-software-factory/  # Miniforge product workflows
│   ├── workflow-financial-etl/     # Data Foundry product workflows
│   ├── agent/          # AI agent implementations
│   ├── loop/           # Inner loop (generate-validate-repair)
│   └── ...
├── bases/              # Runnable applications
│   ├── cli/            # Command-line interface
│   └── lsp-mcp-bridge/ # MCP server bridge
└── projects/           # Deployable projects
    ├── miniforge/      # Miniforge (software factory) project
    ├── miniforge-core/ # MiniForge Core (engine-only) project
    └── miniforge-tui/  # Terminal UI project
```

Each component is independently testable and reusable.

## Testing

```bash
# Run all tests
bb test

# Run tests for specific component
clojure -M:poly test component:workflow

# Run specific test namespace
clojure -M:test -n ai.miniforge.workflow.validator-test

# Run with coverage
bb ccov
```

## Code Style

We use:

- **clj-kondo** for linting (configured in `.clj-kondo/config.edn`)
- **cljfmt** for formatting (automatic via pre-commit)
- **Polylith** for architecture (components, bases, projects)

Style is enforced via pre-commit hooks.

## Common Tasks

### Add a New Component

```bash
# Create component
clojure -M:poly create component name:my-component

# Add to project deps
# Edit projects/miniforge/deps.edn
# Add :ai.miniforge/my-component to :deps

# Run tests
bb test
```

### Run the CLI

```bash
# Via Babashka (fast startup)
bb run workflow list

# Via JVM (full features)
clojure -M:cli workflow run :simple-v2
```

### Debug a Test Failure

```bash
# Run specific failing test
clojure -M:test -n ai.miniforge.workflow.validator-test

# Run with verbose output
clojure -M:test -n ai.miniforge.workflow.validator-test -r pprint

# Check test in REPL
clojure -M:repl
user=> (require '[ai.miniforge.workflow.validator :as v])
user=> (v/validate-workflow test-data)
```

## Pull Request Process

1. **Create Focused PR**
   - One feature or fix per PR
   - Don't mix test fixes with features
   - Small PRs get reviewed faster

2. **Write Clear Description**
   - What: What does this PR do?
   - Why: Why is this change needed?
   - How: How does it work?
   - Test Results: Include test output

3. **Ensure Checks Pass**
   - All tests passing
   - Pre-commit validation passing
   - No linting errors
   - No commented-out tests

4. **Respond to Review**
   - Address all comments
   - Push updates to same branch
   - Re-request review when ready

See PR template for full checklist.

## Getting Help

- **Documentation:** Check [docs/](docs/) directory
- **Examples:** Look at existing components for patterns
- **Issues:** Search [GitHub Issues](https://github.com/miniforge-ai/miniforge/issues)
- **Discussions:** Start a [GitHub Discussion](https://github.com/miniforge-ai/miniforge/discussions)

## Code of Conduct

Be respectful and constructive. We're all here to build something great.

Key principles:

- Critique code, not people
- Assume good intent
- Value clarity over cleverness
- Share knowledge generously
- Fix problems you find

## License

By contributing, you agree that your contributions will be licensed under the same license as the project (see
[LICENSE](LICENSE)).

---

**Ready to contribute?** Start with a
[good first issue](https://github.com/miniforge-ai/miniforge/labels/good%20first%20issue)
or read the [Development Guidelines](docs/development-guidelines.md)!
