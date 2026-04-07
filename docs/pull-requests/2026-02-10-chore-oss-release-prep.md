<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# chore: prepare OSS repo for release

## Overview

Cleans the OSS repo for public release by removing business strategy documents
and tagging enterprise extension points.

## Motivation

With the miniforge-fleet enterprise repo now bootstrapped, business strategy
documents (pricing, revenue targets, GTM, competitive positioning) should live
in the private repo, not in the public OSS codebase. Fleet daemon TODOs should
clearly indicate they are enterprise extension points, not missing OSS features.

## Changes in Detail

### Strategy docs removed
- `specs/informative/oss-paid-roadmap.md` — contained pricing ($49/mo tiers),
  revenue targets ($294K ARR), GTM strategy, conversion triggers
- `specs/informative/software-factory-vision.md` — contained competitive
  positioning against named companies, enterprise pitch language

Both files migrated to `miniforge-ai/miniforge-fleet` repo under
`specs/informative/product-roadmap.md` and `specs/informative/product-vision.md`.

### SPEC_INDEX.md updated
- Removed links to deleted files
- Added note pointing to private repo for strategy docs
- Updated deprecated roadmap reference

### Fleet daemon TODOs tagged
- `bases/cli/src/ai/miniforge/cli/main.clj` — `fleet-start-cmd` and
  `fleet-stop-cmd` TODOs now read "enterprise extension point (see miniforge-fleet)"

### What stays in OSS (no changes)
- Normative specs (N1-N9) — protocol-level documentation
- Informative architecture docs — guides, UX mockups, getting-started
- Deprecated/archived specs — historical context for contributors
- Fleet dashboard web UI — table stakes for local control plane

## Testing Plan

- Lint: clean (0 errors, 0 warnings)
- Format: clean
- Tests: pre-existing failure on main (`babashka/http_client` missing from
  project classpath) — not introduced by this PR

## Deployment Plan

No deployment changes. Documentation-only + TODO annotation.

## Related Issues/PRs

- Companion work: miniforge-fleet repo bootstrapped (miniforge-ai/miniforge-fleet)
- Related: `chore/header-copyright` branch addresses subtitle changes (WS2)

## Checklist

- [x] Strategy docs removed from OSS
- [x] Strategy docs migrated to miniforge-fleet
- [x] SPEC_INDEX.md updated with pointer to private repo
- [x] Fleet daemon TODOs tagged as enterprise extension points
- [x] No normative specs removed
- [x] No code behavior changes
