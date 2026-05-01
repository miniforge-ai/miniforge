<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Miniforge Demo: Spec to PR in 5 Minutes

Watch miniforge plan, implement, test, review, and open a pull request —
on its own codebase.

## What You Will See

1. Miniforge reads a spec describing a new utility function
2. The **planner** analyzes the codebase and decomposes the work into tasks
3. The **implementer** writes the code and tests
4. **Verification gates** check syntax, linting, and test results
5. The **reviewer** self-reviews the diff against the spec
6. The **releaser** creates a branch, commits, and opens a PR

All of this happens autonomously. You just watch.

## Prerequisites

- Completed the [Quickstart](quickstart.md) (`bb bootstrap`)
- An LLM backend: Claude Code CLI, Codex CLI, or an API key
- A GitHub token (for PR creation): `export GITHUB_TOKEN="ghp_..."`
  - Or run without PR creation to see the pipeline without pushing

## Run the Demo

```bash
bash examples/demo/run-demo.sh
```

Or run directly:

```bash
mf run examples/demo/add-utility-function.edn
```

## What the Spec Says

The demo spec asks miniforge to add a `normalize-identifier` function to the
logging component — a pure function that converts arbitrary strings into
keyword-safe identifiers (e.g., `"My Feature Name!"` becomes `"my-feature-name"`).

This is a real, useful addition to the codebase. Miniforge is building itself.

## Expected Output

```text
Parsing workflow spec: examples/demo/add-utility-function.edn
Running workflow: Add string normalization utility to logging component

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Miniforge Workflow Runner
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

→ Phase :explore started
✓ Phase :explore success (0ms)

→ Phase :plan started
  • Agent :plan analyzing codebase...
  • Gate :plan-complete passed
✓ Phase :plan success (1.2m)

→ Phase :implement started
  • Agent :implement writing code...
  • Gate :syntax passed
  • Gate :lint passed
  • Gate :no-secrets passed
✓ Phase :implement success (45s)

→ Phase :verify started
✓ Phase :verify success (8s)

→ Phase :review started
  • Agent :reviewer reviewing diff...
✓ Phase :review success (1.5m)

→ Phase :release started
  • Creating branch, committing, pushing...
  • PR created: https://github.com/miniforge-ai/miniforge/pull/XXX
✓ Phase :release success

✓ Workflow completed
Tokens: 12,450 | Cost: $0.42 | Duration: 4.2m
```

## Inspecting the Results

After the workflow completes:

```bash
# See what files were changed
git diff HEAD~1

# Check the PR (if GITHUB_TOKEN was set)
gh pr view --web

# View execution artifacts
ls ~/.miniforge/artifacts/
```

## Key Takeaways

- **Zero prompt engineering** — the spec describes WHAT, not HOW
- **Policy-governed** — syntax, lint, and test gates prevent bad code
- **Multi-agent** — specialized agents for each SDLC phase
- **Self-improving** — miniforge can build features for itself
- **Cost-efficient** — $0.30-0.50 per feature, intelligent model selection
- **Traceable** — every agent decision is logged in evidence bundles

## Next Steps

- [Write your own spec](user-guide/writing-specs.md)
- [Understand the phases](user-guide/phases.md)
- [Configure LLM backends](user-guide/configuration.md)
