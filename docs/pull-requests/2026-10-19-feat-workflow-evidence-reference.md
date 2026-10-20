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

Depends on #2059 (terminal-phase facts) and #2056, both merged. Final main alignment
also incorporates the separately verified workflow-intent forwarding from #2058.
Failed bundle publication must not expose a completion reference. Default
current-host migration and workflow-level publication refusal propagation remain
separate work. Composed consumers and package were rechecked after main alignment.

## Verification plan

Exercise real native finalization, artifact storage, runner completion, checkpoint
round-trip and current terminal-event journal reopen. Check failed publication,
declared-versus-missing references and explicit rewind. Verify all product consumers,
rebuilt isolated CLI, adversarial standards, normal signed budgets, exact-head
review and full CI. The checkpoint field is optional for existing snapshots;
no rewrite/migration is required. Normal CLI deployment.

## Local verification and adversarial review

- All three phase/workflow/phase-opsv consumers: passing, serially (2m19s).
- Rebuilt CLI: 39,187,288 bytes. Isolated package: 44 tests, 360 assertions,
  passing without production source overlays.
- Kondo: no warnings/errors. All nine changed namespaces match inferred strata.
- Reviewed provenance at the successful finalization boundary, generic workflow
  field ownership, failed-publication omission, checkpoint selection and explicit
  rewind clearing. Existing snapshots remain readable without the optional key.
  Shared assertion support checks actual artifact identity and current journal
  reopen; no allocated ID or arbitrary private phase output is promoted.

## CI composition correction

The explicit OPSV finalization CI command exposed a missing test-only dependency:
the project test alias did not include the shared workflow journal support path.
Added that path rather than duplicating the journal helper. The exact CI command
is verified locally from `projects/miniforge`, without an expanded custom classpath.
Production dependencies and code are unchanged by this correction.
