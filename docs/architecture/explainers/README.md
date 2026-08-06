<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Architecture explainers (ELI9)

Plain-language companion stories to the diagram suites in
[`../diagrams/`](../diagrams/). One shared world (robots, doorkeepers,
stickers, passes) across repos; the tenancy/routing stories live in
`thesium-career/docs/architecture/explainers/`.

1. [`the-fort-building-robots.html`](the-fort-building-robots.html) —
   the main Miniforge architecture: the garage board (work kanban),
   the wiggle test (gates), the two notebooks (evidence bundles +
   event stream), the porch (operator console). Grown-up translation
   table at the end.

These files are CANONICAL here and projected to
`https://miniforge.ai/architectures/` by
`miniforge-ai/miniforge-website` — its `sync-content.yml` pulls them
on merge (via `notify-website.yml` dispatch) or daily. Edit here,
never on the website repo. The story doubles as the simplicity
invariant: if a change to the architecture cannot be told in the
story, the change is suspect.
