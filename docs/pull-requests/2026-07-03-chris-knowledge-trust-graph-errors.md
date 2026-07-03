<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Knowledge Trust Graph Errors

Branch: `fix/knowledge-trust-graph-errors`

## Summary

`knowledge.trust/validate-transitive-trust` already returned validation data for
authority and tainted-isolation failures, but graph-shape failures still threw
`ex-info`. This PR makes circular or missing dependency graph failures return the
same `schema/invalid-with-errors` shape.

## Verification

- `clj-kondo` on the touched trust source and test
- focused `knowledge.trust-test`
- raw exceptions-as-data scan:
  `{:cleanup-needed 27, :fatal-only 126, :local-boundary 16}`
