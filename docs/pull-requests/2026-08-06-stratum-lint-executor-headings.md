<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# chore: stratum-lint headings for dag-executor/executor.clj

**Theme:** executor isolation (prerequisite)
**Stack:** first of three. Followed by the host-git guard and its wiring.

## Problem

`executor.clj` carried no `Layer N` banner headings at all. stratum-lint
skips a file with no headings, so the file had never been checked, and the
next edit to it would have dragged a 200-line mechanical rewrite into an
otherwise small diff — precisely the "mechanical diff hiding a real change"
shape `## Adversarial Review Before Push` in AGENTS.md warns about.

That next edit is the one-line registry change in the third PR of this stack.
Landing the rewrite on its own keeps it readable.

## Changes in Detail

One `bb -m stratum-lint.interface --fix` pass. It adds the `Layer 0/1/2`
banners and `^{:stratum n}` metadata rule 210 requires, and regroups five defs
into the layer the linter infers for them from the same-file reference graph:

| def | moved |
|-----|-------|
| `prepare-docker-executor!` | Layer 1 → Layer 2 (calls `prepare-runtime-executor!`) |
| `with-provenance` | Layer 1 → Layer 2 (calls `capture-provenance`) |
| `with-environment`, `capture-provenance`, `clone-and-checkout!` | shifted up as those two moved past them |

No behaviour change and no signature change.

## Layer

`dag-executor` component. Layers 0, 1, 2 after the pass — within the SL003
budget, so no namespace split is needed.

## Testing

No new tests; this is a restructuring PR and claims that explicitly per
rule 716.

Verified rather than assumed:

- The same 30 defs exist before and after, with no additions or removals —
  checked by extracting the def names from both revisions and diffing the
  sets.
- `dag-executor` component suite green: 423 tests, 1745 assertions.
- Post-fix stratum-lint clean (exit 0), and a second `--fix` pass is a
  no-op, so the file is stable rather than merely passing once.
- `clj-kondo` clean.
