<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: split collector.clj under the layer budget

Historical record. Merged as PR #1843.

## Overview

`collector.clj` was 639 lines across six strata; SL003 allows three. Split
into five namespaces grouped by subject, with `collector.clj` keeping what
assembles a bundle from those pieces.

## Motivation

Pre-existing debt, surfaced when an unrelated change put the file into the
linted set — the same way `event-stream/core.clj` did in PR #1799. Nothing
could touch the file until it was under budget, so this landed on its own
rather than buried inside a behavioural change.

## Changes in Detail

| namespace | holds |
|---|---|
| `compliance-defaults` | template defaults, caller overrides, their merge |
| `phases` | one phase's output, its evidence entry, all phases |
| `collectors` | gathering from workflow state and the event stream |
| `dependency-health` | health from recorded state, or from events |
| `outcome` | outcome evidence and failure attribution |

Eight private forms became public because they are now referenced across a
namespace boundary. References were updated in the seven files that named
the moved functions — all inside this component.

## Testing Plan

Behaviour preservation was verified, not asserted, in two passes:

1. **By name** — the set of 36 top-level forms is identical before and
   after. This also caught a section comment the extraction had stripped.
2. **By body** — each form's source compared against the original after
   normalising the three differences a split may introduce: added alias
   qualification, `defn-` to `defn` where a form crosses a namespace
   boundary, and file-local stratum renumbering. Exactly 4 of 36 differ,
   all only in their stratum tag.

126 tests / 388 assertions; `poly check` OK; kondo clean.

## Related Issues/PRs

- PR #1799 — the same split, for `event-stream/core.clj`
- PR #1844 — the change that surfaced this and depended on it

## Checklist

- [x] Form set identical before and after, verified by comparison
- [x] Form bodies unchanged beyond qualification and stratum tags
- [x] Section headers reattached to their own forms after reordering
- [x] Every file at three strata or fewer
