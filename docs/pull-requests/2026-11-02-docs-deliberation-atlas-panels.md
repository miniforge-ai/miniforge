<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# docs(deliberation): architecture atlas panels

## Overview

Adds the five shared-deliberation architecture panels from snapshot
`a345990` under `docs/architecture/deliberation/`, with `index.html`, a
self-contained viewer that inlines them. The atlas README and the N14/N15
Annex A updates follow in a separate PR, because together they exceed the
600-line PR budget.

## Motivation

The panels show what changed from the original N14, where the trust
boundaries sit, how information flows under scope, how conclusions stay
revisable, and what the development lab can and cannot claim. Each one
separates what the synthetic lab implements from the Ariadne target.

## Changes in Detail

- `diagrams/01`–`05`: the snapshot panels.
- `index.html`: the panel viewer, with no external assets. Unlike the
  snapshot, it:
  - falls back to panel 1 for a fragment that names no panel, instead of
    throwing;
  - follows Back and Forward through a `hashchange` handler;
  - lists the selected panel's title, description and text under "Text of
    this panel", because the SVG's `role="img"` gives assistive technology
    only its title; the panel container is a labelled group;
  - uses explicit `<head>` and `<body>` elements.
- The SVGs stay inlined in the viewer so it works opened from disk, where a
  page cannot fetch sibling files and the transcript needs the SVG text. An
  edit to a panel is made in both places.
- One correction in panel 2, applied in both the SVG and the viewer. The
  snapshot described a CLI process in an empty directory with a timeout.
  The lab now calls the model through the `llm` component's isolated
  client (#2347). The text now reads "Isolated, tool-free llm client call"
  and "Observed usage, or unknown usage on failure, is recorded."
- Panel 5 said timeouts are recorded. The adapter journals any failed call
  as an adapter failure without distinguishing timeouts, so the card now
  says "failed calls and failed cells".
- The viewer's footer link to the README is left out until the README PR
  adds it, so this PR merges with no broken link.
- `render_diagrams.py`, the snapshot's Python generator, is not carried
  over. It had no tests and no task in `bb.edn` (dewey 740). The SVGs are
  now the maintained source.

## Testing Plan

- Every SVG parses as XML.
- Headless Chromium loads `index.html` with no script errors and five panel
  buttons. The edited panel 2 card renders with the new text inside its
  box.
- Fragments `#abc`, `#2.5` and `#9` select panel 1 and `#3` selects panel 3.
  Back after a click restores the previous panel, and the transcript lists
  the panel's text.
- Each panel claim was checked against the merged code: runtime-stamped
  identity, role and impersonation rejection, closed vocabularies,
  choice-point adoption, receipt-backed fixture evidence, the 2 × 5 × 2 × 3
  development matrix (60 cells, 510 activations) and G0 never evaluated.

## Deployment Plan

None. Documentation only.

## Related Issues/PRs

- The lab code: #2334, #2346, #2347.
- Next: the atlas README and the N14/N15 Annex A updates.

## Checklist

- [x] No external assets
- [x] Panel text matches the merged implementation
- [x] Copyright header on the PR doc
