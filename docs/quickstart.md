<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Quickstart: Run Your First Workflow

Get from zero to a miniforge-generated pull request in under 5 minutes.

## Prerequisites

- macOS, Linux, or Windows — see [Platform Support](platform-support.md)
- [Babashka](https://github.com/babashka/babashka#installation) (bb)
- An LLM backend — one of:
  - [Claude Code](https://claude.ai/claude-code) CLI (recommended)
  - [Codex](https://openai.com/codex) CLI
  - An API key (Anthropic or OpenAI)
- An OCI-compatible local container runtime — see "Container runtime" below.
  [Podman](https://podman.io/) is the recommended default; Docker is supported.

Install Babashka:

```bash
# macOS
brew install babashka/brew/babashka

# Linux (static binary; avoids glibc surprises)
curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
chmod +x install
./install --static
```

```powershell
# Windows (PowerShell, native — preview)
# If you hit an execution-policy error first:
#   Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
Invoke-Expression (New-Object System.Net.WebClient).DownloadString('https://get.scoop.sh')
scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
scoop bucket add extras
scoop install babashka
```

> **Native Windows is in beta.** The bb-based workflow runs, but the
> `bb bootstrap` task and the bash demo script still assume a Unix shell.
> If you hit a wall, fall back to **WSL2** or **Git Bash** and follow the
> Linux instructions above. See [Platform Support](platform-support.md) for
> the current matrix and known gaps.

## 1. Clone and Bootstrap

```bash
git clone https://github.com/miniforge-ai/miniforge.git
cd miniforge
bb bootstrap
```

This installs Java, Clojure, linters, Polylith, prefetches the coverage
tool used by `bb ccov`, and (on macOS) brew-installs Podman and
initializes a default `podman machine`. Linux users install Podman via
their distro package manager (`apt install podman`, `dnf install
podman`, `pacman -S podman`); Docker users can stay on Docker by setting
`MINIFORGE_RUNTIME=docker` or `:runtime-kind :docker` in config.

## Container runtime

Miniforge runs every task inside an isolated OCI container. Any
OCI-compatible local runtime works; Podman is the recommended default.

```bash
# Verify the resolved runtime
mf doctor                # full system check, includes runtime block
mf runtime info          # descriptor as data

# Pass-through to the runtime CLI for ad-hoc use
mf runtime run --rm hello-world

# Pin to Docker explicitly (otherwise auto-probed: Podman → Docker)
export MINIFORGE_RUNTIME=docker
```

Auto-probe picks the first runtime whose `info` succeeds in this order:
Podman, then Docker. If you set `MINIFORGE_RUNTIME=podman` and Podman
isn't installed, miniforge fails loud rather than falling back — the
whole point of explicit configuration is honoring your choice. See the
[configuration docs](configuration.md#container-runtime) for the full
config block.

## 2. LLM Backend

Miniforge auto-detects installed agent CLIs. If you have Claude Code or Codex
installed, you're ready. Otherwise, set an API key:

```bash
# Only needed if no agent CLI is installed
export ANTHROPIC_API_KEY="sk-ant-..."
# Or:
# export OPENAI_API_KEY="sk-..."
```

## 3. Run a Workflow

```bash
mf run examples/workflows/simple-refactor.edn
```

You'll see miniforge work through the pipeline:

```text
→ Phase :explore started
✓ Phase :explore success (0ms)

→ Phase :plan started
  • Agent :plan: analyzing codebase, decomposing spec into tasks...
✓ Phase :plan success (1.2m)

→ Phase :implement started
  • Agent :implement: writing code...
  • Gate :syntax passed
  • Gate :lint passed
  • Gate :no-secrets passed
✓ Phase :implement success (45s)

→ Phase :verify started
✓ Phase :verify success (8s)

→ Phase :review started
  • Agent :reviewer: reviewing diff...
✓ Phase :review success (1.5m)

→ Phase :release started
  • Creating branch, committing, pushing...
  • PR created: https://github.com/you/repo/pull/123
✓ Phase :release success

✓ Workflow completed
Tokens: 12,450 | Cost: $0.42 | Duration: 4.2m
```

## 4. Write Your Own Spec

Create a file called `my-feature.edn`:

```clojure
{:spec/title "Add health check endpoint"

 :spec/description
 "Add a /health endpoint that returns HTTP 200 with a JSON body
  containing the service name, version, and uptime."

 :spec/intent {:type :feature}

 :spec/constraints
 ["Use the existing HTTP server framework"
  "No new dependencies"]

 :spec/acceptance-criteria
 ["GET /health returns 200"
  "Response body is valid JSON"
  "Response includes :service, :version, :uptime-ms keys"]}
```

Run it:

```bash
mf run my-feature.edn
```

## 5. Try a Markdown Spec

You can also write specs as Markdown:

```markdown
---
title: Fix login timeout
description: |
  The login form times out after 5 seconds on slow connections.
  Increase the timeout to 30 seconds and add a loading indicator.
intent:
  type: bugfix
constraints:
  - No changes to the auth backend
  - All existing login tests must pass
---

## Context

Users on mobile networks report seeing "Request timed out" errors
when trying to log in. The current timeout is hardcoded to 5000ms
in the fetch call.
```

Run it the same way:

```bash
mf run fix-login-timeout.md
```

## What Just Happened?

When you ran `mf run`, miniforge executed a full SDLC pipeline:

1. **Explore** — Scanned the codebase, loaded relevant files into context
2. **Plan** — Decomposed your spec into a task DAG with dependencies
3. **Implement** — Generated code via an LLM agent, validated through gates
4. **Verify** — Ran tests, checked coverage thresholds
5. **Review** — Self-reviewed the diff against your spec and constraints
6. **Release** — Created a branch, committed changes, opened a PR

Every phase is governed by policy gates. If the code doesn't pass syntax
checking, linting, or tests, the agent repairs and retries automatically.

## Next Steps

- [Demo Guide](demo.md) — Watch miniforge improve itself
- [Writing Specs](user-guide/writing-specs.md) — Spec format reference
- [Phases](user-guide/phases.md) — What each pipeline phase does
- [Configuration](user-guide/configuration.md) — LLM backends and tuning
