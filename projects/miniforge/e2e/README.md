# End-to-End (E2E) Tests

This directory contains true end-to-end tests that exercise the complete system with real backends.

## Characteristics

- **Real LLM backends** - Uses actual Claude API, not mocks
- **Complete workflows** - Runs full workflow execution end-to-end
- **Real I/O** - File operations, git operations, network calls
- **Requires credentials** - Needs API keys and environment setup
- **Not run in CI** - Runs separately due to cost, time, and
  credential requirements

## vs Integration Tests

Integration tests (`../integration/`) use mocks to validate infrastructure
wiring and are fast enough for CI. E2E tests validate the complete system
with real backends.

## Running E2E Tests

E2E tests will be added when project tests are integrated into CI pipeline.

```bash
# Future usage (when implemented):
clojure -M:e2e -m clojure.test.runner
```

## Test Organization

Structure mirrors the main codebase:

```text
e2e/
  ai/
    miniforge/
      workflow/
        <e2e tests for workflows>
      agent/
        <e2e tests for agents>
```
