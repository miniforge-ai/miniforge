# fix: retry the backend preflight probe before refusing the workflow

## Overview

The workflow runner probes the backend CLI once before a workflow starts,
with a 30 s budget:

```bash
claude -p 'Reply with exactly {"ok":true}' --output-format json --max-turns 1
```

One timeout refused the whole run with `Backend preflight failed for claude`.

This PR retries the probe a bounded number of times (default 3 attempts,
2 s pause between failed attempts, both operator-tunable), logs every
failed attempt as `:llm/preflight-retry` through the logging component,
and keeps the anomaly data shape unchanged when every attempt fails.

Base branch: `main`. Depends on: nothing.

## Motivation

On 2026-09-03 four `eval/codex-traps` reps (ru2, ru3, ru3b, and an
earlier rep of the same shape) were refused with the inner message
`Process timed out after 30000ms`. Each refusal landed within about 30 s
of a previous long run ending. A hand probe (`claude -p "reply ok"`)
answered inside a minute every time.

The failure mode is a CLI still releasing a prior session when the probe
arrives. A single 30 s sample cannot distinguish that from a CLI that is
down, so the preflight fails closed on a transient condition and the
operator loses the run.

## Changes in Detail

### `bases/cli/resources/config/cli/workflow-runner.edn`

Two new keys under `:backend-preflight`: `:attempts 3` and
`:retry-pause-ms 2000`. Both are configuration, not code constants,
because different hosts want different values.

### `preflight_support.clj`

1. Layer 0: `default-preflight-attempts` (3) and
   `default-preflight-retry-pause-ms` (2000) with docstrings stating the
   rationale. These are the silent-config fallbacks only.
2. Layer 2: `backend-preflight-attempts` and
   `backend-preflight-retry-pause-ms` accessors, resolved through the
   merged workflow-runner config with `get` and the default.

### `preflight_retry.clj` (new)

The retry loop pushed `preflight.clj` to a fourth layer (stratum-lint
SL003), so it lives in its own namespace.

1. Layer 0: `stderr-logger` (warn-and-above to stderr, used when the
   runtime context carries no `:logger`; stderr so `quiet` callers that
   parse stdout are not affected), `log-attempt-failed!` (one
   `:llm/preflight-retry` warn entry with `:attempt`, `:attempts`,
   `:elapsed-ms`, `:will-retry?`, `:backend`, `:cmd-path`, `:error-type`,
   `:error-message`), and `pause-before-retry!`.
2. Layer 1: `probe-backend-with-retries` runs `probe/run-backend-probe`
   up to the configured count, logs each failure, pauses between
   attempts, and returns the first success or the last failure.

### `preflight.clj`

1. Layer 1: `verify-backend-probe!` now calls
   `retry/probe-backend-with-retries` and throws the same
   `:anomalies/unavailable` anomaly as before, with the same data keys
   (`:backend :cmd :cmd-path :cmd-version :probe-response`).
2. Layer 2: `run-backend-preflight!` reads `:logger` from the context,
   falling back to the stderr logger.

Retries apply to every failed probe, not only timeouts. A non-timeout
failure (non-zero exit, unexpected output) returns fast, so the extra
cost is two short pauses; a timeout costs the full budget per attempt,
which is the case the retry exists for.

### `preflight_test.clj`

1. New: `run-backend-preflight-retries-timed-out-probe-test`. A stubbed
   `process/run-cli-command` times out twice then returns a successful
   Claude result envelope. Asserts three invocations, preflight passes,
   and exactly two `:llm/preflight-retry` warn entries with attempts
   `[1 2]`, a non-negative `:elapsed-ms`, `:will-retry? true`, and
   `:error-type "backend_preflight_timeout"`.
2. New: `run-backend-preflight-succeeds-first-time-without-retry-log-test`.
   One call, no retry entries.
3. Updated: `run-backend-preflight-fails-closed-on-bad-cli-health-test`
   now stubs the process runner (production shape) instead of the probe
   function, asserts all three attempts ran and were logged, and pins the
   anomaly data key set.
4. The error-wrapper test pins the retry pause to zero so it stays fast
   now that a failed probe retries.

## Testing Plan

1. `clojure -M:test:dev -e '(require ...) (clojure.test/run-tests
   (quote ai.miniforge.cli.workflow-runner.preflight-test))'` — the
   namespace passes locally.
2. `clj-kondo` and `stratum-lint` on the four changed Clojure files.
3. CI runs the full suite.

## Deployment Plan

Ships with the next CLI build. No migration. Operators who want the old
behaviour set `:attempts 1` in `workflow-runner.edn`.

## Related Issues/PRs

1. The 2026-09-03 `eval/codex-traps` refusals (ru2, ru3, ru3b).
2. Rule 006 named constants, rule 007 configuration is data, rule 716
   tests ship with code.

## Checklist

- [x] Retry loop with configurable attempts and pause
- [x] `:llm/preflight-retry` logged per failed attempt via the logging component
- [x] Anomaly data shape unchanged on exhaustion
- [x] Unit test: stubbed runner times out twice then succeeds
- [x] Standards gap analysis (006, 007, 210, 716)
- [ ] Copilot review cycle settled
- [ ] Merged
