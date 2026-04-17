<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Bump standards pack to pick up Polylith composition + tool rules

## Context

The `miniforge-standards` repo just gained two new Polylith rules:

- `frameworks/polylith-composition.mdc` (dewey 311) — normative rule
  covering 8 architectural requirements (bricks own production logic,
  bases are boundary adapters, interfaces are contracts, validation
  gates workflows, etc.)
- `frameworks/polylith-tool.mdc` (dewey 312) — operational MDC
  describing how agents must behave to comply: preflight reasoning,
  the 7-step canonical workflow, role-specific operational
  requirements (implementation / review / test / release).

Plus two earlier updates that landed upstream in the same pass:
the "Unique Interface Names (CRITICAL)" section added to the existing
`frameworks/polylith.mdc` (standards PR #4) and the removal of the
private "Consuming Repos" tables (standards PR #5) in preparation
for flipping the standards repo to public visibility.

## What changed

- `.standards` submodule pointer bumped to `ab00452` (the merge of
  standards PR #7, which transitively includes PRs #4, #5, #6).
- `components/phase/resources/packs/miniforge-standards.pack.edn`
  recompiled via `bb standards:pack`. Rule count goes from 23 → 25
  (the two new polylith-* rules). Existing rules also pick up the
  Unique Interface Names addition to the original `polylith.mdc`.

## Verification

```bash
$ bb standards:pack
Compiled 25 rules
  Failed: 0
  Wrote components/phase/resources/packs/miniforge-standards.pack.edn

$ grep -c 'polylith-composition|polylith-tool|\
           Polylith Workspace Architecture Tool|\
           Polylith Architecture and Composition' \
     components/phase/resources/packs/miniforge-standards.pack.edn
7
```

Pack loads; content references are intact.

## Follow-up

- The polylith-compliance-remediation spec (`work/`) can now be run —
  its Group 2 constraint ("no two components share an interface name")
  is enforceable from the pack, and the composition + tool rules are
  available to agents during that work.
- Fleet / thesium / control / risk repos can pull the same updated
  `.standards` submodule HEAD and run their own `standards:pack`
  equivalent to pick up the new rules.
