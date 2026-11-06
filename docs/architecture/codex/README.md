<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Thesium Codex: worries indexed by situation

The codex stores what is dangerous, indexed by the situation you are in, not by
what is abstractly true. Every problem it raises is anchored to a receipt, a scar
from a failure that actually happened. Miniforge consults it at the start of a
phase, records whether the agent looked, and measures where misses got past it.

Open [the architecture atlas](index.html) for four selectable, full-size panels.
The SVGs are the maintained source; `index.html` inlines the same panels, so an
edit to a panel is made in both. They use the house style of the other suites in
[`../diagrams/`](../diagrams/README.md): blue header bands, pale typed fills,
and solid / dashed / dotted strokes for built / partial / spec-only.

| Panel | Question it answers |
|---|---|
| [1. Knowledge model](diagrams/01-knowledge-model.svg) | What is in the codex, and what does `consider(situation)` return? |
| [2. Delivery](diagrams/02-delivery.svg) | Which situation does each phase enter, and how do the warnings reach the agent? |
| [3. Gate and gaps](diagrams/03-gate-and-gaps.svg) | What fails a phase, and how are misses classified and read out? |
| [4. Curation](diagrams/04-curation.svg) | How are entries written, admitted, promoted and retired? (dotted: vault-side) |

## Where the code is

| Concern | Code |
|---|---|
| Parse, walk and render the node graph | `components/codex/` (`node`, `graph`, `pegs`, `core`, `render`) |
| Phase routing, the pin, the consultation summary | `components/phase-software-factory/src/.../codex_pin.clj` |
| Pull tools `consider_situation`, `answer_peg` | `bases/mcp-context-server/` (`codex_tool.clj`, `tool-registry.edn`) |
| The `:codex-consultation` gate | `components/gate/src/.../codex_consultation.clj` |
| Miss ledger, buckets, report, telemetry, retrodiction | `components/codex-gap/`, recorded at phase leave by `gap_wiring.clj`; `bb codex-gap-report`, `bb codex-gap-peg-telemetry`, `bb codex-gap-retrodict` |

The codex itself is not in this repository. It is a directory of markdown nodes,
read from `MINIFORGE_CODEX_PATH`. With the variable unset the capability is off,
and every part of the code above treats that as a normal, quiet state.

## What panel 4 does not know

Curation (harvest, admission, borrowed scars, promotion, retirement) happens in
the vault and is specified in the Codex specs T1 and T2, neither of which is in
this repository. Panel 4 is drawn dotted from the spec sections the code cites
and from earlier session records. It has not been checked against the vault.

## The story

[`explainers/the-worry-book.html`](explainers/the-worry-book.html) tells the same
architecture in the shared robots world of the other explainers. If a change to
the codex cannot be told in the story, the change is suspect.
