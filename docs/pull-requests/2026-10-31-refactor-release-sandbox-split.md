<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(release-executor): split sandbox.clj into two namespaces (rule 210)

## Overview

Mechanical, no behaviour change. A rule 210 split of
`components/release-executor/src/ai/miniforge/release_executor/sandbox.clj` into two namespaces,
each within the 3-layer budget.

## Motivation

`sandbox.clj` spans 5 call-graph layers against a budget of 3, so any edit to it fails SL003 in
the pre-commit hook. The release push probe in `sandbox/remote-branch-sha!` needs a one-line fix
(a stacked PR), and that fix should pass stratum-lint without warn mode.

## Changes in Detail

1. `sandbox.commands` (new): main's Layers 0 and 1, the single commands every sandbox operation
   is built from. These are `exec!`, the container path and branch-name guards, gh and git output
   parsing, and the operations that run one git or gh command (or a fixed short sequence):
   `check-gh-auth!`, `detect-default-branch`, `commit-changes!`, the HTTPS push fallback, PR
   reuse, `edit-pr-body!`, `diff-stats`, `count-test-defs`. Two layers.
2. `sandbox`: main's Layers 2 to 4 (now 0 to 2) and `track-operation`. It also has 8 one-line
   aliases that keep each moved public name a caller outside uses (`exec!`,
   `validate-safe-branch-name`, `check-gh-auth!`, `detect-default-branch`, `commit-changes!`,
   `edit-pr-body!`, `diff-stats`, `count-test-defs`). Three layers.

All 34 definitions move unchanged. There are four kinds of edit:

1. `commands/` qualification of calls to moved definitions, with their continuation lines
   realigned.
2. Public visibility for 7 moved definitions that `sandbox` now reaches.
3. The new stratum numbers in `sandbox`.
4. The aliases and the namespace docstrings.

`core.clj` is unchanged: it calls every name through `sandbox` as before.

`sandbox` calls `commands` directly, not through its aliases. Calling through them would put
`sandbox` back at 4 layers. Redefining `sandbox/exec!` therefore still steers core's direct
calls, but no longer the sandbox operations. Two tests that captured commands that way now
redefine `commands/exec!` as well:

1. `pr-redaction-test`, for `create-pr!` and `edit-pr-body!`.
2. `release-write-redaction-test`, for `step-commit`'s `commit-changes!`.

Limits of the aliases, as in the artifact-session split: they do not carry `:arglists`, and
reloading only `sandbox.commands` at a REPL leaves an alias holding the old function until
`sandbox` reloads.

## Testing Plan

1. Form comparison against main: every top-level form of main's file and of the two new files
   was read with `*read-eval*` false. The normalization strips `commands/` prefixes, treats
   `defn-` as `defn`, and renames `#()` gensyms. The two sides hold the same multiset of 34
   forms, each alias names a moved definition, and no definition carries metadata other than
   `:stratum`. A one-token change to the new file makes the comparison fail.
2. clj-kondo on the four changed source and test files: no errors or warnings.
3. stratum-lint in the pre-commit hook: both namespaces within budget.
4. Every release-executor test namespace, run under the shared JVM lock.

## Deployment Plan

Ships with the next release; no configuration or data change.

## Related Issues/PRs

1. Precedent: the artifact-session split (#2260) and the semantic-analyzer split (#2093).
2. Unblocks the push probe credential fix, stacked on this PR.

## Checklist

- [x] Every definition moved unchanged (normalized comparison)
- [x] Each namespace within 3 layers
- [ ] Release-executor test namespaces pass
- [ ] CI green, Copilot comments resolved
