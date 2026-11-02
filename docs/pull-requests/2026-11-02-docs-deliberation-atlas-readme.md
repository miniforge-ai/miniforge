<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# docs(deliberation): atlas README and N14/N15 implementation status

## Overview

The last PR from the deliberation snapshot `a345990`. It adds the atlas README
and points N14 and N15 Annex A, and the SPEC_INDEX, at the lab as it landed.

## Motivation

Until now Annex A of N14 said the 0.3.0 amendments were not yet implemented,
and Annex A of N15 said no harness component exists. Both are false once the
workspace stack, the harness and the lab base have merged. The README is the
atlas's run guide and limitations statement.

## Changes in Detail

- `docs/architecture/deliberation/README.md`, from the snapshot, corrected
  against the merged code:
  - The SVGs are the maintained source; `render_diagrams.py` was not carried
    over (#2348).
  - Tests run with `poly test` through `projects/deliberation-lab`. The
    snapshot's `clojure -M:test` alias does not exist in the project.
  - A run directory holds `journal/` and `report.md` (#2347).
  - The live adapter is the `llm` component's isolated Claude client, not a
    CLI process, and it has no per-call dollar guard.
  - Observations go through admission as the verifier's receipt-backed
    evidence. They count only when the closing turn's board showed them, and
    the README states that the `independent` condition therefore earns no
    evidence credit (#2346).
- `index.html`: the footer link to the README, held back in #2348.
- N14 Annex A.1: the core now carries the 0.3.0 amendments, provenance
  attestation and the lab. A.2 adds that the lab accepts synthetic fixtures
  only.
- N15 Annex A.1: a development runner exists. The section lists what it
  provides and what a conforming gate harness still needs.
- `SPEC_INDEX.md`: a link from the N14 entry to the atlas.

## Testing Plan

- markdownlint on every changed Markdown file.
- Each README and annex claim was checked against the merged code and the
  stacked PRs it names: the commands and the `:poly` alias, the run layout,
  the adapter options, the challenge cap, the duplicate-operation guard, the
  closure reserve and the 60-cell, 510-activation development matrix.

## Deployment Plan

None. Documentation only. Merge after #2346, #2347 and #2348, which the
README describes.

## Related Issues/PRs

- Stacked on #2348 (panels).
- Describes #2335, #2345, #2346 and #2347.

## Checklist

- [x] Every factual claim checked against the code it describes
- [x] Copyright header on the PR doc
