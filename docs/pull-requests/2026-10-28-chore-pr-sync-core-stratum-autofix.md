<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# chore: Stratum-lint autofix of pr_sync/core.clj (no logic change)

## Overview and motivation

`components/pr-sync/src/ai/miniforge/pr_sync/core.clj` and its test had
Layer headings but no stratum metadata. The first edit to either arms the
pre-commit `stratum-lint --fix`, which rewrites the whole file (about 900 and
560 changed lines), far over the commit and PR budgets. This PR is only that
rewrite, so the config-overwrite fix for the TUI repo writers can follow as a
small diff.

## Layer and dependencies

Mechanical. Based on the shared config writer branch only so the follow-up has
one base; it uses nothing from it.

## Changes

Output of the pinned `stratum-lint --fix`: regenerated headings,
`^{:stratum n}` metadata on every def, defs regrouped by layer.

## Verification plan

Behaviour-neutral, checked by reading every top-level form of the old and new
file without eval: the same multiset of forms (62 in core.clj, 47 in the test;
compared by `pr-str` with reader gensyms normalized, so metadata, comments and
whitespace are ignored), an identical `ns` form, the same set of defs, and
`:stratum` as the only metadata added or changed on any def. The namespace
loads in its new order; all 94 pr-sync tests pass.

## Scope and deployment

No runtime change. SL003 (8 layers) is pre-existing; a rule 210 split is
separate work.
