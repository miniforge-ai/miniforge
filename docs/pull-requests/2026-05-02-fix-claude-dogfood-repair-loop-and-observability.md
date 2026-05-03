## Summary

This PR packages the next set of Claude dogfood fixes from the `behavioral-verification-monitor` run on `main`.

The run surfaced three real product issues:

1. repair attempts could claim `:already-implemented` without artifact evidence and slip through phase boundaries
2. active workflows could be marked stale while agent and tool activity was still flowing
3. CLI status output ignored `MINIFORGE_HOME`, so isolated dogfood runs printed the wrong events directory

It also exposed two supporting runtime issues that made diagnosis slower than it should have been:

1. tool-hook stdin handling could deadlock on unbounded reads
2. planner / LLM streaming fallback behavior could look like a silent hang even when the backend had already failed or
  returned empty streaming output

## What Changed

### Repair loops now fail closed

- tighten implementer parsing so prose-only or bare `:already-implemented` claims are not accepted as successful repair
  output
- prefer real file artifacts over status claims when both are present
- reject `:already-implemented` during repair attempts unless there is concrete artifact evidence
- add an `implement` phase boundary check so prior review/verify failures cannot be bypassed by an unverified
  `:already-implemented` handoff

### Planner and runtime observability are more reliable

- stop tool-hook stdin from blocking on full-stream `slurp`
- accept planner-submitted plan artifacts even when the backend marks the response unsuccessful
- recover EDN plan submissions from failed stdout when the planner clearly produced a plan
- add a single retry path for “analysis completed but no plan submission” outcomes
- treat blank successful streaming responses as a fallback case instead of a long fake stall
- preserve stderr diagnostics and shorten the post-kill wait path for stalled planner subprocesses
- print tool activity in the terminal callback path so tool-heavy phases do not look dead

### Workflow freshness now tracks real activity

- update supervisory-state workflow freshness on agent, tool, gate, LLM, and task activity events
- stop deriving false `Workflow stale` attention while a long-running phase is actively doing work

### CLI path reporting now honors isolated homes

- make CLI app-config prefer `MINIFORGE_HOME` for its home/events/config paths
- keep the profile-based path as the fallback when the env override is unset

## Validation

- focused agent / implement / planner / LLM / event-stream tests passed during development
- focused lint for the new CLI and supervisory-state fixes passed
- focused workspace tests passed:
  - `ai.miniforge.cli.app-config-test`
  - `ai.miniforge.supervisory-state.accumulator-test`
- live Claude dogfood rerun confirmed:
  - planner uses MCP context tools during exploration
  - bogus repair-loop `:already-implemented` drift no longer recurs
  - review redirects back to implement with concrete findings
  - nested tool activity is visible again instead of looking fully silent

## Follow-up

This PR should be followed by another Claude dogfood rerun on `main` to confirm the stale-attention and home-dir fixes
in the same end-to-end workflow run.
