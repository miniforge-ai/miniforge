# Fix Claude Plan Stream Progress

## Summary

This PR fixes a real dogfood failure in the Claude planner path after `#767`.

The workflow now gets past backend preflight, but Claude planning could still
fail with a false stagnation timeout during tool-heavy planning. The immediate
symptoms from live dogfood were:

- `:plan` failed with `Adaptive timeout: Stagnation timeout`
- the progress monitor reported only `2` chunks and `1` unique chunk
- planner artifact-session diagnostics logged a missing `artifact.edn`
- timeout errors dropped the raw streamed stdout that would have made the
  failure diagnosable

The root issue was that stream supervision was treating raw stdout lines as the
progress signal instead of parsed semantic activity. When Claude spent time in
MCP/tool-use without substantive text deltas, the progress monitor could time
out even though the agent was still active.

## Changes

- record planner/LLM progress from parsed stream events instead of raw stdout
  lines
- treat tool-use, heartbeat, and result events as semantic progress signals
- preserve raw streamed stdout in timeout error payloads when parsed content is
  empty
- add regression coverage for:
  - tool-use progress preventing false stagnation
  - raw stdout preservation on streaming timeout failures

## Validation

- `clj-kondo --lint` on touched LLM files
- focused `ai.miniforge.llm.interface-test`
- full `bb pre-commit`

## Dogfood Context

This stacks on the already-committed preflight result-wrapper fix on
`fix/claude-preflight-result-wrapper`.

Live dogfood sequence on this branch:

1. backend preflight was fixed to accept Claude's wrapped success result
2. workflow then advanced into `:plan`
3. `:plan` exposed the next real issue: false stagnation during tool-heavy
   planner execution
4. this PR addresses that second failure mode directly
