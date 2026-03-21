# N1 Conformance Test Suite

This directory contains the N1 Architecture conformance tests as specified in PR9.

## Test Files

1. `n1_architecture_test.clj` - End-to-end workflow execution tests
2. `event_stream_test.clj` - Event stream completeness verification
3. `agent_context_handoff_test.clj` - Context passing between phases
4. `protocol_conformance_test.clj` - Protocol implementation verification
5. `gate_enforcement_test.clj` - Gate enforcement and inner loop validation

## Running Tests

```bash
# Run all conformance tests
bb test:conformance

# Or with clojure CLI directly
clojure -M:conformance -e "(require 'clojure.test) \
  (require 'conformance.n1_architecture_test) \
  (require 'conformance.event_stream_test) \
  (require 'conformance.agent_context_handoff_test) \
  (require 'conformance.protocol_conformance_test) \
  (require 'conformance.gate_enforcement_test) \
  (clojure.test/run-tests 'conformance.n1_architecture_test \
                           'conformance.event_stream_test \
                           'conformance.agent_context_handoff_test \
                           'conformance.protocol_conformance_test \
                           'conformance.gate_enforcement_test)"
```

## N1 Spec Requirements Verified

These tests verify compliance with N1 §8.2:

- ✅ End-to-end workflow execution (spec → PR with evidence)
- ✅ Agent context handoff between phases
- ✅ Inner loop validation and repair
- ✅ Gate enforcement blocks phase completion
- ✅ Event stream completeness

## Notes

These are integration-level conformance tests that verify the N1 spec requirements
are met by the actual implementation. They differ from unit tests in that they:

1. Test across component boundaries
2. Verify spec compliance rather than implementation details
3. Focus on observable behaviors and contracts
4. Are more brittle but provide stronger conformance guarantees
