<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(agent,cli): the tool hook confines paths and refuses credential and remote access

## Overview

Builds on the default-deny tool hook (#2251). The hook now checks what a tool call
reaches, not only which tool it is: file tools stay inside the session's worktree and
out of its git metadata and agent config, and Bash commands that read credentials,
print tokens, contact the network, or change git's remotes and hooks are refused.

## Motivation

After #2251 a role could still use its allowed tools on anything: Read `/etc/passwd`
or `~/.aws/credentials`, Grep `/`, Write `.git/hooks/pre-commit` (run by miniforge's
next git command), or write `.claude/settings.local.json` for a later session in the
same worktree. The Bash deny list covered destructive commands only;
`cat ~/.aws/credentials`, `gh auth token`, `env | curl ...` and
`git remote add x ... && git push x` were allowed.

## Changes in Detail

1. `tool-policy.edn`:
   - `:tool/path-keys`: the inputs that name a path, per tool, now including
     `consider_situation`'s `codex_path`;
   - `:tool/glob-keys`: the glob inputs;
   - `:tool/protected-paths`: agent CLI and MCP config files a later session loads,
     and the capsule session directory (skills, commands and rules stay editable);
   - `:bash/max-command-length`;
   - `:bash/deny-patterns` for credential files, token and environment printing,
     network clients where a command starts, git pushes and remote edits, risky git
     config keys (case-insensitive) and `GIT_*` overrides, and shell paths into `.git`
     and the protected config. Repetition is bounded.
2. `tool-policy` namespace: a closed malli schema for the policy file
   (`read-tool-policy`, which fails the call on a mismatch), `worktree-relative`
   (canonical, so `/var` and `/private/var` agree and a symlink is judged where it
   points), `escaping-glob?` and `path-refusal`.
3. `evaluate-tool-use` takes the session (`:role`, `:root`, `:path-ok?`). A file tool
   with no root is refused. Bash commands over the length limit are refused before any
   pattern runs.
4. CLI: the hook command adds `--root <workdir>`; `mf hook-eval` moves to
   `commands/hook_eval.clj`, which passes pr-lifecycle's `safe-worktree-path` (the PR
   fix loop's guard: git dir, common dir, hooks dir from `core.hooksPath`, `.git`
   components) as `path-ok?`. The agent component cannot require pr-lifecycle, which
   depends on it.

## Testing Plan

1. `tool-policy-test`: credential, token, network and git commands are refused, and
   ordinary build and git commands (including commit messages that name a tool) are
   allowed; the length limit; each single defect in the shipped policy fails the
   schema; worktree confinement; only loaded agent config is protected; `codex_path`.
2. `hook-eval-test` (CLI): a real repository with `core.hooksPath`, through
   pr-lifecycle's guard; root spellings with and without the macOS `/private` prefix.
3. The real `bb miniforge hook-eval` under babashka: a write inside the worktree is
   allowed; `.git/config`, the hooks directory, `/etc/passwd`, `.claude/` settings and
   a missing `--root` are refused.
4. All deny patterns take 21 ms on an adversarial 32 KB command.
5. Copilot review fixes: a newline counts as a command separator ((?m) patterns); path
   inputs are trimmed before the check, as the MCP handlers trim them; the path as
   written (symlinks unresolved) is also checked against the protected list and is what
   pr-lifecycle's guard sees, so a symlinked `.claude/` cannot hide protected settings.
   Each path is checked both as given and trimmed (handlers differ on trimming), a raw
   `..` component is refused before normalization, both the resolved and the as-written
   forms must pass the guard, and `\` separators are turned into `/` before the
   protected-path match.
6. Independent adversarial review; findings fixed or listed below.

## Deployment Plan

Ships with the next release. Agents lose access to paths outside their worktree.

## Known limits

1. A Bash deny list can be bypassed (indirection, quoting, aliases, a script the agent
   writes). The environment allowlist for the agent process and capsule egress rules
   are the controls meant to hold; this is defense in depth.
2. When git cannot report the worktree's metadata (a root that is not a repository,
   or one git refuses as dubious ownership), every path is refused.
3. MCP context reads resolve against the source root, while the hook checks paths
   against the session's working directory.
4. The hook sees a search tool's inputs, not its results: `context_grep` and
   `context_glob` can return files a direct read is refused, and the context server's
   glob walker follows directory symlinks. Confining search results belongs in the
   context server (follow-up).

## Related Issues/PRs

1. #2251 (default-deny tool hook), #2260 (artifact-session split), #2100 (the worktree
   guard this reuses).

## Checklist

- [x] Tests fail on main and pass here
- [x] Independent review; findings fixed or listed above
- [ ] CI green, Copilot comments resolved
