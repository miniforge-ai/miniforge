# fix: missing closing paren in reviewer.clj invoke-fn

## Overview
Parse error in `reviewer.clj` was blocking all CLI startup. The `invoke-fn`
closure added by PR #346 (phase lifecycle telemetry) had 4 closing parens
where 5 were required, causing a compile-time bracket mismatch.

## Motivation
Every `bb miniforge` invocation failed at namespace load time with:
```
Unmatched delimiter: }, expected: ) to match ( at [501 7]
```
This blocked all dogfood runs.

## Layer
Bug fix / Syntax

## Base Branch
`main`

## Depends On
None.

## Changes in Detail
- `components/agent/src/ai/miniforge/agent/reviewer.clj`: changed `))))` to
  `)))))` at line 618 to close `build-review-result`, gate-let, if,
  outer-let, and the `invoke-fn` itself.

## Strata Affected
- `ai.miniforge.agent.reviewer`

## Testing Plan
- [x] `bb miniforge --help` loads cleanly after fix
- [x] CLI namespace loads without parse error

## Deployment Plan
- Merge immediately — blocks all dogfood activity.

## Related Issues/PRs
- Root cause introduced in PR #346 (phase lifecycle telemetry for reviewer)

## Risks and Notes
- Single-character fix, no behavior change.

## Checklist
- [x] Isolated onto a clean branch from `main`
- [x] Added PR doc under `docs/pull-requests/`
