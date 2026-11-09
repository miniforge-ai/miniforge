<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# docs: Thesium Codex architecture atlas and story

## Overview

Adds `docs/architecture/codex/`:

- four house-style panels, a self-contained `index.html` atlas, and a README
- an ELI9 story, *The Worry Book*, set in the same world as the other
  explainers

The repo had no architecture doc for the Thesium Codex. Every "codex" in
`docs/architecture/` refers to the OpenAI Codex CLI agent backend.

## Motivation

The operator asked for an architectural diagram in house style and a story
for the Thesium Codex. Curation is to be drawn dotted, because it lives in
the vault and the Codex specs, outside this repository.

## Changes in Detail

- `diagrams/01-knowledge-model.svg`: covers the vault as read-only input,
  the node graph (situation → discriminator/peg → problem or resolution,
  with scars as anchors), and what `consider` returns (strategic-first
  landings, coverage, escalations, pegs, anomalies).
- `diagrams/02-delivery.svg`: covers `phase->situation` routing for plan,
  implement, review, release and verify; delivery by pin, prompt section or
  pull; and the `:codex/consultation` record, including tri-state
  `:pin-read?`.
- `diagrams/03-gate-and-gaps.svg`: covers the `:codex-consultation` gate
  verdicts on plan and implement, warnings kept in gate history, the
  first-leak-wins buckets, the ledger files, and the three
  `bb codex-gap-*` read-outs.
- `diagrams/04-curation.svg`: shows harvest, borrowed scars, the admission
  test, horizons and boards, dedup, promotion (§5.6) and retirement (§4.4).
  All of it is dotted, with a sources band stating that it was not verified
  against the vault. Only the read boundary and the two telemetry bridges
  are solid.
- `index.html`: the atlas, built on the same pattern as
  `docs/architecture/deliberation/index.html`. It has panel tabs, a text
  transcript, and no external assets.
- `README.md`: the panel table, a map from each concern to its code, and
  what panel 4 does not know.
- `explainers/the-worry-book.html`: the story, with a grown-up translation
  table. It lives under `codex/` rather than `docs/architecture/explainers/`,
  so the website sync does not publish it until the operator chooses to.

## Testing Plan

Documentation only, so per dewey 716 there are no test changes. For a docs
PR, gate 1 means checking every factual claim against the repo, and each
card was checked against the code it names. That check corrected three
claims before this PR opened:

- `gate-history.edn` is written for every gated run, not only when the
  codex is configured.
- The gap report's slots are coverage, routing, anchoring, delivery,
  attention, learning and the review queue.
- The read-only reviewer session reads back answers only, not context reads.

The atlas page and the story were rendered in headless Chromium. Panel
switching and the transcript work, and there are no console errors.
markdownlint is clean.

## Deployment Plan

None. The files are static. Publishing the story to miniforge.ai would mean
moving or copying it into `docs/architecture/explainers/`.

## Related Issues/PRs

- Builds on the codex work in #1629, #2303, #2307 and #2308.

## Checklist

- [x] Every claim checked against the code
- [x] Renders checked; no console errors
- [x] markdownlint clean
- [ ] CI green
- [ ] Copilot review resolved
