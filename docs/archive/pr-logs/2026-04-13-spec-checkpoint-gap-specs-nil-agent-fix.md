<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Spec checkpoint, gap-coverage specs, and nil-agent fix

**PR:** [#532](https://github.com/miniforge-ai/miniforge/pull/532)
**Branch:** `claude/mini-forge-progress-review-IfSSa`

## Summary

Progress review checkpoint capturing spec completeness (~45-50%) and OSS
readiness (~75%) across all normative specs N1-N11. Adds 18 work specs
to close every identified gap between the work directory and the spec
vision. Fixes a silent bug where nil agents produced stub artifacts, and
removes abandoned workflow-run artifacts from the repo root.

## Changes

### Progress checkpoint
- `docs/progress-review-2026-04-13.md` (create) — per-spec completeness
  estimates, OSS readiness audit, work spec inventory, gap analysis, and
  tiered priority recommendations

### Gap-coverage work specs (18 new)
- `work/n03-event-type-completeness.spec.edn` — N3: gate, tool, inter-agent, milestone events
- `work/n04-knowledge-safety-pack.spec.edn` — N4: prompt-injection detection, tripwires
- `work/n04-kubernetes-diff-parsing.spec.edn` — N4: K8s manifest diff for policy gates
- `work/n05-cli-command-wiring.spec.edn` — N5: audit and wire all CLI commands
- `work/n05-http-api-decision.spec.edn` — N5: resolve winsock vs HTTP/SSE
- `work/n06-sensitive-data-scanning.spec.edn` — N6: credential/PII scanning
- `work/n06-compliance-metadata.spec.edn` — N6: data classification, retention
- `work/n07-opsv-workflow.spec.edn` — N7: OPSV skeleton + DISCOVER phase
- `work/n07-opsv-converge-verify-actuate.spec.edn` — N7: remaining phases
- `work/n08-oci-governance.spec.edn` — N8: RBAC, listener capabilities, audit
- `work/n08-privacy-retention.spec.edn` — N8: privacy levels, retention policies
- `work/n08-otel-trace-context.spec.edn` — N8: OpenTelemetry, W3C Trace Context
- `work/n09-pr-work-item-model.spec.edn` — N9: readiness, risk, automation tiers
- `work/n09-external-pr-read-only-eval.spec.edn` — N9/N4: read-only policy eval
- `work/n09-provider-native-checks.spec.edn` — N9: GitHub Check Runs
- `work/n09-credential-management.spec.edn` — N9: credential encryption, rotation
- `work/n10-tool-execution-audit.spec.edn` — N10: tool audit trail, approval flows
- `work/oss-integration-test-coverage.spec.edn` — 6 high-priority integration tests
- `work/README.md` (modify) — add gap-coverage spec inventory table

### Nil-agent fix
- `components/workflow/src/ai/miniforge/workflow/agent_factory.clj` (modify) —
  `:reviewer`, `:releaser`, `:observer` now throw ExceptionInfo instead of
  returning nil; unknown agent types also throw; `handler-only-agents` set
  documents the constraint
- `components/workflow/src/ai/miniforge/workflow/configurable.clj` (modify) —
  remove unused `_gates` binding (gates were created then discarded)
- `components/workflow/test/ai/miniforge/workflow/agent_factory_test.clj` (modify) —
  test throw behavior for handler-only and unknown agent types

### Abandoned artifact cleanup
- `src/ai/miniforge/generated/impl.clj` (delete) — stub from aborted workflow run
- `test/ai/miniforge/generated/impl_test.clj` (delete) — tests for the stub
- `test/feature_test.clj` (delete) — placeholder test

## Test plan

- [ ] `bb test :workflow` passes (agent_factory_test updated for throw behavior)
- [ ] No regressions in `bb test:poly` (deleted files not referenced by deps.edn)
- [ ] Verify progress doc renders correctly
- [ ] Spot-check 2-3 gap specs for format consistency

---
26 files changed, +1422 -173
