<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: every operator consumer can retry runs and re-evaluate PRs

## Overview

Seventh of eight stacked PRs. Adds the CLI policy evaluator. Every process that
consumes operator interventions now registers the resume launcher (#1915, with
fixes in #1923 and #1916) and the evaluator. Before this, `:retry` and
`:retry-from-phase` always failed `:no-resume-launcher`, and `:re-evaluate`
failed `:no-policy-evaluator`.

## Motivation

Nothing in production registered either handle. Retry verbs and process-global
targets go to whichever consumer sees them first. Every consumer must carry
both handles, or those verbs fail depending on which process won.

## Changes in Detail

- `workflow-runner.control/register-process-handles!` registers the degradation
  manager, launcher and evaluator in one place. The runner path and the
  runnerless `start-process-control!` both call it. A launcher that cannot be
  built on the calling thread is not registered, so it cannot clear one that
  was.
- Each registered run records its origin, where a retry of it will run, with
  its runner's pid; releasing the run drops the pid.
- The consumer reads the same events root as the rest of the process. It is
  stopped at process exit, so a retry being verified records an outcome. The
  process installs one exit hook, with its first consumer; the hook stops
  whichever consumer is current at exit, so a stop and restart adds no hook.
- Policy evaluator: `evaluate-external-pr` over the packs installed under
  `<home>/packs`, against `gh pr diff`. It does not use the classpath
  built-ins `mf policy list` also shows. Changed files come from the diff, so
  glob-scoped packs apply. It refuses when there is no PR, when a pack failed
  to load, when no packs are installed, or when the diff is missing or empty
  (a PR with no changes).
- `gh pr diff` is abandoned after 30 s and its process tree killed: a
  re-evaluation runs inside the pass other processes wait on.

## Testing Plan

- Evaluator: coordinates, every refusal (an empty diff included), the real
  loader with a failing pack, and the real evaluator with a glob-scoped
  pack.
- `fetch-pr-diff`: a `gh` that does not answer is killed and yields nil; an
  answer in time is the diff.
- Wiring: both paths register the same handles, origin recording and release,
  the events root, a nil launcher not clearing a registered one, and one exit
  hook across start, stop, start that stops the second consumer.
- Smoke against a temp `MINIFORGE_HOME` (with #1918): a retry of a run with a
  recorded origin reached `verified`, the child running in the origin. A run
  without one failed `:resume-origin-unknown`. After the cursor was deleted,
  the redelivered retry reached `verified` again with the one child it had.
- Pre-commit hook per commit.

## Deployment Plan

No migration. Runs started before this change have no recorded origin, so a
retry of them is refused `:resume-origin-unknown`.

## Related Issues/PRs

Stack, in merge order:

1. `feat/resume-flags` (#1913, merged)
2. `feat/operator-async-resume` (#1914, merged)
3. `feat/operator-verification-pool` (#1920, merged)
4. `feat/resume-launcher` (#1915, merged)
5. `fix/resume-launcher-review` (#1923, merged)
6. `feat/resume-launcher-hardening` (#1916)
7. `feat/shared-process-handles` (#1917, this PR)
8. `feat/operator-serve` (#1918)

## Checklist

- [x] Tests for new behaviour
- [x] Messages in the CLI system catalog
- [ ] Review comments addressed; CI green
