<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Compliance Scanner Demo — YC Week

## Setup (before demo)

```bash
# 1. Ensure miniforge is built
cd /path/to/miniforge

# 2. Initialize the demo repo as a git repo (scanner needs git ls-files)
cd examples/demo-polyglot-repo
git init && git add -A && git commit -m "demo polyglot repo"
cd ../..

# 3. Verify linters are available
which cargo        # Rust (Clippy)
which clj-kondo    # Clojure
which ruff         # Python (install: pip install ruff)
# Optional: swiftlint, eslint, golangci-lint

# 4. Dry run
bb miniforge scan examples/demo-polyglot-repo --report false
# Should show: 20 violations (14 policy + 6 linter)
```

---

## Demo Script

### Opening (30 seconds)

> "Miniforge is an autonomous SDLC platform. One of its core capabilities
> is compliance scanning — a single command that analyzes any polyglot
> repository across three tiers: policy rules, language linters, and
> LLM-powered behavioral analysis."

### Act 1: Repository Analysis (30 seconds)

```bash
bb miniforge init examples/demo-polyglot-repo
```

> "First, miniforge analyzes the repository. It detects languages, build
> systems, and frameworks from file extensions and marker files — all
> driven by a single EDN config file, no code. This repo has Rust, Swift,
> Clojure, Python, TypeScript, and Kubernetes manifests."

**Key points:**
- Technologies detected automatically
- Policy packs selected based on what's in the repo
- Config written to `.miniforge/config.edn`

### Act 2: Three-Tier Scan (60 seconds)

```bash
bb miniforge scan examples/demo-polyglot-repo
```

> "Now we scan. Three things happen:
>
> First, **policy packs** — declarative rules in EDN — scan for
> organizational standards. Hardcoded secrets, unsafe Rust blocks,
> Swift force-unwraps, Clojure map-access patterns, TODO comments.
> These are rules no linter covers because they're YOUR organization's
> policies.
>
> Second, **language linters** run automatically. Clippy for Rust,
> clj-kondo for Clojure, ruff for Python — whatever's installed.
> The scanner detects which linters apply from the tech fingerprints
> and runs them. Output is parsed through a data-driven ETL layer —
> adding a new linter is an EDN entry, not code.
>
> Third — and this is the differentiator — **semantic analysis**.
> An LLM evaluates your code against behavioral rules like stratified
> design, simple-made-easy, and naming conventions. Things no regex
> or linter can catch."

**Key output to highlight:**
- Policy violations: secrets in setup.sh, unsafe Rust, Swift force-unwraps
- Linter violations: Clippy findings, clj-kondo namespace mismatch
- Combined count with breakdown

### Act 3: Auto-Fix (30 seconds)

```bash
bb miniforge scan examples/demo-polyglot-repo --execute --report false
```

> "The scanner classifies every violation as auto-fixable or needs-review.
> With `--execute`, it applies the mechanical fixes — regex replacements,
> linter `--fix` commands — and leaves the semantic violations for human
> review. One command, every language."

**Show the git diff after:**
```bash
cd examples/demo-polyglot-repo && git diff --stat
```

### Act 4: The Architecture (30 seconds)

> "Everything is data-driven:
>
> - **Tech fingerprints** — one EDN file declares 45 languages, 25 build
>   systems, 21 frameworks
> - **Policy packs** — rules are EDN, not code. We ship 11 reference packs
>   with 53 rules. Organizations add their own.
> - **Linter mappings** — field-mapping specs in EDN transform any linter's
>   JSON output into our canonical violation shape
> - **Prompt templates** — LLM judge prompts are EDN templates, not
>   hardcoded strings
>
> Adding a new language, linter, or policy rule is a config change. No code."

### Closing (15 seconds)

> "This is one command for compliance across any polyglot repo. Policy rules
> that no linter covers. Language linters that run automatically. LLM analysis
> for the things humans used to review manually. All data-driven, all
> extensible, all from `bb miniforge scan`."

---

## Fallback Plan

If semantic analysis is slow during live demo:
- Skip `--semantic` and mention it verbally
- Show pre-recorded output from a previous run
- Or run with `--semantic --report false` which completes faster

If a linter isn't installed:
- The scanner gracefully reports "not installed (skipped)"
- This is actually a feature to demo — shows graceful degradation

---

## Key Numbers

| Metric | Value |
|--------|-------|
| Languages in tech-fingerprints.edn | 45 |
| Build systems detected | 25 |
| Frameworks detected | 21 |
| Reference policy packs | 11 |
| Rules across all packs | 53+ |
| Linters supported | 6 (Clippy, clj-kondo, ESLint, ruff, SwiftLint, golangci-lint) |
| Demo repo violations | 20 (14 policy + 6 linter) |
| Auto-fixable | 17 of 20 |
