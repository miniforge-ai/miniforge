<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Architecture explainers (ELI9)

Plain-language companions to the Ariadne tenancy/policy architecture
(`../tenancy-ownership-access.md`) and its diagram suite. One shared
story-world (robots, doorkeepers, stickers, passes) with the
miniforge factory story in the
[miniforge-ai/miniforge](https://github.com/miniforge-ai/miniforge)
repo under `docs/architecture/explainers/`.

1. `tenancy-for-a-nine-year-old.md` (+ `eli9-*.svg`) — editable
   markdown source for the tenancy story.
2. `routing-for-a-nine-year-old.md` — editable markdown source for
   the policy/routing story.
3. `the-robot-helpers-and-the-sticker-rules.html`,
   `your-house-their-clubhouse-and-the-passes.html` — the published
   story pages (self-contained HTML with inline figures). CANONICAL
   here; projected to `https://miniforge.ai/architectures/` by
   `miniforge-ai/miniforge-website` (its sync-content workflow pulls
   on `notify-website.yml` dispatch or daily cron). Edit here, never
   on the website repo. When a story's markdown changes, update its
   HTML in the same PR — the HTML is what ships.
