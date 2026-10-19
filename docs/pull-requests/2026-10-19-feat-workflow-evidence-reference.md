<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Carry published evidence references into workflow completion

## Purpose and layer

Application integration for N7/N3 terminal completion. The native finalization
boundary exposes its acknowledged bundle ID through the existing canonical
`:workflow/evidence-bundle-id` field. Workflow execution promotes that declared
phase-result fact and checkpoints it. It never substitutes an allocated input ID
or inspects OPSV-private output fields. Explicit rewind clears the prior terminal
reference; ordinary resume preserves it.

## Dependencies and scope

Depends on #2056 and done-phase-facts before merge. Failed bundle publication must
not expose a completion reference. Default current-host migration, workflow intent
and workflow-level publication refusal propagation remain separate work.

## Verification plan

Exercise real native finalization, artifact storage, runner completion, checkpoint
round-trip and current terminal-event journal reopen. Check failed publication,
declared-versus-missing references and explicit rewind. Verify all product consumers,
rebuilt isolated CLI, adversarial standards, normal signed budgets, exact-head
review and full CI. The checkpoint field is optional for existing snapshots;
no rewrite/migration is required. Normal CLI deployment.

## Local verification and adversarial review

- All three phase/workflow/phase-opsv consumers: passing, serially (1m51s).
- Rebuilt CLI: 39,187,209 bytes. Isolated package: 41 tests, 339 assertions,
  passing without production source overlays.
- Kondo: no warnings/errors. All nine changed namespaces match inferred strata.
- Reviewed provenance at the successful finalization boundary, generic workflow
  field ownership, failed-publication omission, checkpoint selection and explicit
  rewind clearing. Existing snapshots remain readable without the optional key.
  Shared assertion support checks actual artifact identity and current journal
  reopen; no allocated ID or arbitrary private phase output is promoted.
