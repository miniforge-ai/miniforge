<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: add career-profile workflow family

## Overview
Adds the first Thesium Career workflow family component to Miniforge:
`workflow-career`.

This slice ships the initial `career-profile` workflow as data, wires the
component into Miniforge classpath composition, and adds a composition
test that proves the shared workflow loader and registry can discover it.

## Motivation
The career stack now has:
- ETL and KG storage in `thesium-workflows`
- a retrieval surface in `kg-retrieval`
- an MCP-style KG tool adapter in `kg-mcp-tools`

What was still missing was the first Miniforge workflow definition that
consumes that tool boundary. Per the Thesium Career architecture, agentic
profile/rank/lens flows belong in Miniforge workflows rather than inside
the workflows repo or the product app.

This PR starts that layer with the first workflow: `career-profile`.

## Base Branch
`main`

## Depends On
None.

## Changes In Detail
- Adds new component:
  `components/workflow-career`
- Adds `career-profile-v1.0.0.edn` under:
  `components/workflow-career/resources/workflows/`
- Encodes the workflow as data:
  KG tool requirements,
  policy-pack identity,
  output contract hints, and
  phase budgets/defaults
- Adds composition tests in:
  `components/workflow-career/test/ai/miniforge/workflow_career/catalog_test.clj`
- Wires the component into:
  root `deps.edn`,
  `bb.edn`, and
  `CONTRIBUTING.md`

## Testing Plan
Executed in `/tmp/cc-miniforge-workflow-career-profile`:
- `clojure -M:test -n ai.miniforge.workflow-career.catalog-test -n ai.miniforge.workflow.loader-test -n ai.miniforge.workflow.registry-test`

Coverage added:
- workflow resource loads through the shared loader
- workflow registry discovery sees `career-profile` on the classpath

## Deployment Plan
No migration is required.

Consumers pick up the workflow family through classpath composition:
1. include `workflow-career`
2. the shared loader/registry now see `career-profile`
3. later slices can add execution handlers and sibling career workflows
   without changing the kernel

## Architecture Notes
- This PR intentionally adds only the first workflow definition, not the
  full career workflow family.
- It also does not yet add the `career-rank` or Lens workflows.
- The workflow is data-only in this slice; execution handlers and
  chaining can land in follow-on PRs.

## Related Issues/PRs
- `thesium-career` PR #15
- `thesium-workflows` PR #8

## Checklist
- [x] Added `workflow-career` component
- [x] Added data-backed `career-profile` workflow
- [x] Added composition tests for loader and registry discovery
- [x] Wired the component into Miniforge classpath composition
