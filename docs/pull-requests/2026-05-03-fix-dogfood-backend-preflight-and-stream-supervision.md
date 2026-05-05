## Summary

This stacked PR follows `#766` and packages the next Claude dogfood fixes from the `behavioral-verification-monitor`
run on `main`.

The remaining startup/runtime problem had two distinct parts:

1. Miniforge was still trusting that the configured backend CLI was healthy, without proving which binary it was about
   to execute or whether that exact binary could answer a trivial request before entering `:explore`
2. the LLM stream reader could sit in one long blocking read, which made an idle-but-open subprocess look like a silent
   hang because progress supervision could not fire until another line arrived or the process exited

On this host that mattered because the failure is version-specific, not “Claude generally broken”:

- `/Users/chris/.local/bin/claude` `2.1.126` answers the non-interactive probe correctly
- `/opt/homebrew/bin/claude` `2.1.89` wedges in `claude -p` mode

So the product needs to stamp the exact inherited binary path/version it will use and fail before workflow execution if
that resolved backend is unhealthy.

## What Changed

### Backend startup now resolves and stamps the real CLI binary

- resolve the backend command from the inherited `PATH` the same way Miniforge will actually launch it
- print backend name, resolved path, and `--version` output before execution starts
- fail closed when the configured CLI command is missing from `PATH`
- fail closed when the version probe itself is unhealthy

### Claude gets a real non-interactive startup probe

- run a bounded Claude CLI preflight before workflow execution:
  - `claude -p "Reply with exactly {\"ok\":true}" --output-format json --max-turns 1`
- run that probe against the resolved binary path, not a freshly spawned shell lookup
- fail closed with probe diagnostics when that exact CLI binary is wedged, exits non-zero, or returns unexpected output

### Other CLI backends still get a bounded health check

- non-Claude CLI backends use the same resolved binary path with a bounded 10-second completion probe
- the failure payload now includes backend name, resolved path, version, and probe response details

### CLI stream supervision is no longer blind during idle stdout

- `process-stream-lines` now polls the stream queue instead of sitting inside one long blocking `readLine`
- progress-monitor timeout checks can now fire while stdout is idle but the process is still alive
- clean EOF is still treated as clean EOF, not misclassified as a timeout
- blank `exit 0` CLI output continues to fail closed as `empty_success_output`

## Validation

- `clj-kondo` on touched CLI and LLM files: clean
- changed-bricks test sweep from the worktree: no failures in the observed run
- focused regression coverage added for:
  - backend stamping and fail-closed preflight anomalies
  - idle stream supervision while waiting for output
  - clean EOF without synthetic timeouts
  - blank successful CLI output

## Live Behavior

- the source-root / MCP anchoring fix is split into `#766`
- this PR makes the remaining backend issue explicit: Miniforge now tells humans and agents which CLI binary it is
  using before the run starts
- if workflow startup resolves to the older wedged Homebrew Claude binary, Miniforge should now fail before `:explore`
  with the stamped path/version and preflight failure payload instead of drifting into a silent planner stall

## Follow-up

- merge `#766` first so this PR stays a narrow stacked follow-up
- rerun Claude dogfood on `main` after both land to confirm:
  - startup provenance is correct
  - the inherited Claude binary is stamped correctly
  - unhealthy backend selection fails loudly before planner execution
