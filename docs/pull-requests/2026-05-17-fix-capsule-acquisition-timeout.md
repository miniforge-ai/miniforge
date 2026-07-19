<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Fix: capsule acquire-environment! wall-clock timeout

## Overview

The 2026-05-16 event-log-tool-visibility dogfood (and the PR #893
pre-commit history) repeatedly stalled inside Docker capsule
acquisition — image pulls, daemon hangs, network blocks. PR #895
mocked the test side; this PR adds the production-side guard so a
stuck OCI runtime fails fast (default 120 s) instead of blocking the
workflow indefinitely.

## Motivation

`OciCliExecutor.acquire-environment!` calls into `shell/sh` /
`ProcessBuilder` for image lookup, image pull, container create, and
workspace bootstrap. None of those calls had a wall-clock timeout, so
a wedged Docker daemon turned into a wedged workflow. Same shape as
the test hang PR #895 traced — but the underlying root cause was
always in production code; the mock just got the tests off our backs.

## Changes

- New `default-acquisition-timeout-ms` (120 000 ms) +
  `with-acquisition-timeout` helper in
  `dag-executor.protocols.impl.runtime.oci-cli`. Runs the body in a
  future and `deref` with deadline; on timeout, cancels the future and
  returns `result/err :acquire-timeout` with the configured limit in
  `:data`.
- `OciCliExecutor.acquire-environment!` body wrapped in
  `with-acquisition-timeout`. Per-call override via
  `env-config[:acquisition-timeout-ms]`. `nil` or non-positive
  disables the guard (escape hatch for tests that mock the whole
  acquire path).
- 4 new tests in `oci-cli-test`: happy-path pass-through, timeout
  fires with the expected error shape, nil/0/negative bypasses, and
  the inner future actually gets cancelled.

## What this does NOT fix

- `KubernetesExecutor` (`kubernetes.clj:288`) and
  `WorktreeExecutor` (`worktree.clj:566`) keep their existing
  `available?` / acquire shape. Worktree's acquire is local-fs only
  and doesn't hang on external services; Kubernetes is deferred to a
  follow-up because the API-server hang surface is different (HTTPS
  - client SDK) and warrants its own design pass.
- Individual `shell/sh` calls inside the body still have no
  per-subprocess timeout. The outer `with-acquisition-timeout`
  guarantees the wall-clock ceiling; if a future tightening reveals
  cancellation isn't propagating to a runaway subprocess, the
  follow-up is to switch `run-runtime-process` over to
  `(.waitFor process timeout TimeUnit/MILLISECONDS)` with
  `.destroyForcibly` on miss.

## Testing Plan

- [x] `clojure -A:test:dev -M -e "(require 'clojure.test
  '[ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-test])
  (clojure.test/run-tests
    'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-test)"`
  → 21 tests, 43 assertions, 0 failures.
- [x] `bb lint:clj` clean.
- [ ] `bb pre-commit` on the pushed branch.

## Related

- PR #895 — test-side mock for the same hang surface.
- PR #893 — original `bb pre-commit` hang surface that surfaced
  this code path.
- Dogfood findings: `project_dogfood_findings_2026_05_16` — Blocker
  follow-up note on capsule-acquisition.
