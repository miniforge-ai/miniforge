<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: share resource cleanup failure policy

## Scope and motivation

Base: main. Extract the reviewed scheduler cleanup policy into a small reusable
resource-cleanup component. Workflow cleanup must be able to release all owned
resources without masking fatal, interrupted or structured failures. Copying that
policy into workflow or importing event-stream internals would violate the shared
component boundary.

Preserve event-stream compatibility aliases and behavior. Keep the public surface
limited to existing failure propagation and two-operation cleanup contracts. This
PR is the foundation for workflow ownership fixes, not the ownership wiring itself.

## Verification plan

Exercise the shared interface and exception graph, retain scheduler fault tests,
validate all consuming project compositions and the packaged CLI. Keep inferred
strata at three or fewer, normal commit/PR budgets, signed hooks, fresh review and
all CI checks. Review exact identity, suppression cycles and interruption handling.

All four consumer projects passed both event-stream and resource-cleanup suites
serially (1m33s), including the JVM interruption-flag regression. The fresh CLI
build is 39,216,010 bytes. Isolated package tests passed 12 tests/40 assertions
with no production source overlays. The actual-base standards scan is clean;
inferred strata remain within three. The complete change is 168 reportable
lines, including tests, so it fits one normal signed commit.

## Deployment and review

This foundation depends on the merged scheduler cleanup in #2075. Existing
event-stream consumers use compatibility aliases through the new public
interface; no workflow behavior or wire contracts change. The four project
compositions and development/test compositions include the component. Deploy
through the normal package build; reverting this PR restores the local policy.

- [x] Trace ordinary, fatal, structured, interrupted and cyclic failure paths.
- [x] Verify consumers, package and standards without concurrent JVM runs.
- [ ] Complete signed hooks, exact-head review and all CI checks before merge.

CI's task-entry load check identified an additional composition: the direct
`bb miniforge` development task. Its explicit classpath now includes the shared
component as well. Validate all task entry namespaces, not only the packaged CLI.
