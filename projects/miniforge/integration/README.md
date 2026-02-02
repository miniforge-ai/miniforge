# Integration Tests

This directory contains integration tests that validate component wiring and infrastructure setup using mocks.

## Characteristics

- **Mock backends** - Uses mock LLMs and services for speed
- **Infrastructure validation** - Confirms components are wired together correctly
- **Fast execution** - Suitable for CI pipeline
- **No credentials needed** - Runs without API keys or external services
- **Comprehensive coverage** - Tests integration points, callbacks, state management

## vs E2E Tests

E2E tests (`../e2e/`) use real backends and validate the complete system
end-to-end. Integration tests validate that the infrastructure is correctly
assembled without the cost and time of real API calls.

## Running Integration Tests

```bash
# From repo root:
clojure -M:dev:test -e \
  "(require 'ai.miniforge.workflow.meta-agent-test) \
   (clojure.test/run-tests 'ai.miniforge.workflow.meta-agent-test)"
```

## Test Organization

Structure mirrors the main codebase:

```text
integration/
  ai/
    miniforge/
      workflow/
        meta_agent_test.clj       - Meta-agent infrastructure tests
      agent/
        <integration tests for agents>
```

## What to Test Here

- Component initialization and configuration
- Data flow between components
- State management and transitions
- Callback and event handling
- Error handling and recovery
- API contracts and protocols
