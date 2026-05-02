<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: [810] Enforce Miniforge.ai header and copyright on published content compliance pass

**Branch:** `fix/compliance-810-enforce-miniforge-ai-header-and-copyright-on-published-content`
**Date:** 2026-05-01

---

```yaml
generated-by: miniforge-compliance-scanner
rule-id: 810
rule-title: "Enforce Miniforge.ai header and copyright on published content"
fix-type: mechanical
violations-fixed: 67
files-changed: 67
date: 2026-05-01
```

---

## Summary

Automated compliance pass applying mechanical fixes for **Enforce Miniforge.ai header and copyright on published
content** (Dewey 810).
67 violations fixed across 67 files.

## Files Changed

Each row below received the canonical multi-line `<!-- … -->` header at line 1. The applied header is the same in every
case (Title / Author / Copyright / Apache 2.0), so the table is condensed to file paths.

| File |
|------|
| `docs/pull-requests/2026-04-26-fix-release-windows-clojure.md` |
| `docs/pull-requests/2026-04-23-feat-pr-lifecycle-fsm.md` |
| `docs/pull-requests/2026-04-29-feat-exceptions-as-data-linter.md` |
| `docs/pull-requests/2026-04-23-feat-workflow-supervision-fsm.md` |
| `docs/pull-requests/2026-04-24-feat-run-pr-and-artifact-projections.md` |
| `docs/pull-requests/2026-04-22-feat-phase-outcome-events.md` |
| `PR-552.md` |
| `docs/pull-requests/2026-04-30-feat-runtime-adapter-phase-2-podman-supported.md` |
| `docs/pull-requests/2026-04-30-local-mode-fidelity-archive-persist-prompt-fix.md` |
| `docs/pull-requests/2026-04-30-feat-dependency-attribution-classifier.md` |
| `specs/informative/I-CANDIDATE-REVIEW-WORKFLOW.md` |
| `docs/pull-requests/2026-04-22-feat-formalize-workflow-supervision-specs.md` |
| `docs/pull-requests/2026-04-27-refactor-extract-content-hash-component.md` |
| `docs/pull-requests/2026-04-27-fix-windows-build-classpath-separator.md` |
| `docs/pull-requests/2026-04-25-refactor-configurable-workflow-compiled-machine.md` |
| `docs/pull-requests/2026-04-25-refactor-loop-task-fsm-slice.md` |
| `docs/pull-requests/2026-04-27-docs-exception-cleanup-inventory.md` |
| `docs/pull-requests/2026-04-25-feat-configurable-machine-fsm.md` |
| `docs/pull-requests/2026-04-23-feat-workflow-machine-snapshot-resume.md` |
| `docs/pull-requests/2026-04-22-fix-precommit-kondo-warnings.md` |
| `specs/informative/I-PACK-ATTACHMENT-PRECEDENCE.md` |
| `docs/user-guide/architecture.md` |
| `docs/pull-requests/2026-04-30-feat-runtime-adapter-phase-4b-runtime-policies.md` |
| `specs/normative/N4-delta-policy-compilation-contract.md` |
| `docs/pull-requests/2026-04-25-refactor-fsm-cleanup-runtime-dependency.md` |
| `docs/user-guide/configuration.md` |
| `docs/pull-requests/2026-04-30-feat-boundary-component.md` |
| `docs/pull-requests/2026-04-30-codex-tool-parity.md` |
| `docs/pull-requests/2026-04-29-feat-runtime-adapter-phase-1-oci-cli-executor.md` |
| `docs/pull-requests/2026-04-29-fix-dag-executor-event-soft-dep-race.md` |
| `docs/pull-requests/2026-04-27-feat-anomaly-component.md` |
| `docs/pull-requests/2026-04-29-spec-external-dependency-health-and-failure-attribution.md` |
| `docs/pull-requests/2026-04-22-fix-phase-outcome-standards-followup.md` |
| `docs/pull-requests/2026-04-29-fix-web-dashboard-approval-handler-soft-dep-race.md` |
| `docs/pull-requests/2026-04-23-fix-workflow-supervision-dry-factories.md` |
| `docs/pull-requests/2026-04-30-feat-dependency-health-projection.md` |
| `docs/pull-requests/2026-04-23-fleet-spec-deltas.md` |
| `docs/pull-requests/2026-04-29-feat-response-chain-component.md` |
| `docs/archive/pr-logs/2026-04-10-fix-210-nil-safe-map-access.md` |
| `docs/pull-requests/2026-04-30-chore-bump-standards-submodule-post-merge.md` |
| `docs/user-guide/phases.md` |
| `docs/pull-requests/2026-04-24-refactor-standard-workflow-machine-phase-authority.md` |
| `docs/demo.md` |
| `docs/user-guide/writing-specs.md` |
| `docs/pull-requests/2026-04-24-feat-fsm-reachability-guards.md` |
| `docs/pull-requests/2026-04-27-fix-release-dispatch-tag.md` |
| `docs/pull-requests/2026-04-30-feat-runtime-adapter-phase-3-selection-doctor-cli.md` |
| `docs/design/workflow-methodologies.md` |
| `docs/demo/compliance-demo.md` |
| `docs/pull-requests/2026-04-28-fix-dogfood-worktree-phase-failure.md` |
| `docs/pull-requests/2026-04-25-refactor-dag-checkpoint-resume-authority.md` |
| `docs/pull-requests/2026-04-23-refactor-learning-loop-authority.md` |
| `docs/pull-requests/2026-04-24-chore-gitignore-work-failed.md` |
| `docs/pull-requests/2026-04-27-feat-rich-decision-agent-context.md` |
| `docs/pull-requests/2026-04-29-feat-dependency-attribution-taxonomy.md` |
| `docs/pull-requests/2026-04-30-feat-runtime-adapter-phase-4a-bootstrap-docs.md` |
| `docs/pull-requests/2026-04-23-refactor-workflow-supervision-boundary.md` |
| `docs/pull-requests/2026-04-22-feat-unified-workflow-machine.md` |
| `docs/pull-requests/2026-04-25-refactor-dag-resume-machine-snapshot-convergence.md` |
| `docs/pull-requests/2026-04-29-fix-dag-executor-gate-test-isolation.md` |
| `docs/pull-requests/2026-04-29-fix-release-pr-doc-body-sync.md` |
| `docs/pull-requests/2026-04-29-refactor-exceptions-as-data-foundation-cleanup.md` |
| `docs/pull-requests/2026-04-25-refactor-implicit-fsm-cleanup-slice.md` |
| `docs/pull-requests/2026-04-25-refactor-workflow-validator-compiler-unification.md` |
| `docs/pull-requests/2026-04-29-spec-capsule-runtime-adapter.md` |
| `docs/quickstart.md` |
| `docs/pull-requests/2026-04-29-fix-supervisory-run-snapshot-attachment.md` |

## Verification

- Re-run `bb miniforge run docs/compliance/miniforge-scan.md` to confirm zero violations for this rule
- All fixes are mechanical — no semantic changes

---
*Auto-generated by the Miniforge compliance scanner.*
