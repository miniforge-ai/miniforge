# fix(polylith): restore workspace structure gate

## Summary

This PR restores Polylith workspace cleanliness as a blocking check instead of a best-effort convention, then fixes the
fresh-`main` verification issues that the restored gate exposed.

The result is that `poly check`, changed-bricks testing, and full `bb pre-commit` all run green again on a clean
branch.

## Problem

`poly check` was not in the blocking path.

That allowed:

- internal component dependencies to drift past public interfaces
- project dependency declarations to fall out of sync with actual usage
- invalid workspace wiring to land without failing pre-commit or CI

The repo was no longer structurally clean even though normal lint/test paths still passed.

## Structural Changes

- fixed illegal internal deps by routing through public interfaces
- restored missing project deps for `data-foundry`, `miniforge`, `miniforge-core`, and `miniforge-tui`
- removed the invalid `bases/etl` component dependency
- re-exported the needed `bb-test-runner` and `progress-detector` interface functions
- added `bb poly:check`
- added `poly:check` to `bb pre-commit`
- added a blocking CI `structure` job that runs `bb poly:check`
- added regression coverage for the new `progress-detector` public interface surface
- documented the structure gate in development guidance

## Verification Path Stabilization

Restoring the structure gate surfaced a second class of fresh-`main` issues in the real changed-bricks path.

This PR also fixes those so the blocking verification path is deterministic again:

- made Claude adapter decision delivery honor an adapter-provided `:decisions-dir`
- updated the Claude adapter decision-delivery test to use a temp decisions root
- removed the event-stream sink test's dependency on the ambient default events home
- resolved `true` / `false` to absolute paths in `bb-proc` tests
- updated the `llm` stream parser test to use the current progress-monitor-aware arity
- removed the stale unused binding from the touched `progress-detector` interface file
- updated `scripts/test-changed-bricks.bb` so native-store bricks can run in isolated JVMs instead of the aggregate
  runner

## Validation

- `bb poly:check`
- focused interface regressions for:
  - `ai.miniforge.progress-detector.interface-test`
  - `ai.miniforge.bb-dev-tools.core-test`
  - `ai.miniforge.response-chain.interface.malli-validation-test`
- focused fresh-`main` verification regressions for:
  - `ai.miniforge.adapter-claude-code.interface-test`
  - `ai.miniforge.event-stream.sinks-test`
  - `ai.miniforge.bb-proc.core-test`
  - `ai.miniforge.llm.interface-test`
  - `ai.miniforge.pipeline-pack-store.interface-test`
- escalated `bb scripts/test-changed-bricks.bb`
- escalated `bb pre-commit`
