<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Replace verbose full-result pprint with compact operator summary in

**PR:** [#964](https://github.com/miniforge-ai/miniforge/pull/964)
**Branch:** `mf/replace-verbose-full-result-pprint-with--0a42eaaa`

## Summary

Replace verbose full-result pprint with compact operator summary in display.clj.

## Files Changed

- `bases/cli/resources/config/cli/messages/en-US.edn` (modify) — localized strings for the new compact operator summary.
- `bases/cli/src/ai/miniforge/cli/workflow_runner/display.clj` (modify) — replaces verbose full-result pprint with the
  compact summary; reads phase data from the canonical execution context; ANSI codes use `\u001b[…m` Unicode escapes per
  repo convention.
- `bases/cli/test/ai/miniforge/cli/workflow_runner/display_output_test.clj` (modify) — verifies compact summary output
  against the canonical workflow result shape.
- `bases/cli/test/ai/miniforge/cli/workflow_runner/display_test.clj` (modify) — coverage for the new summary path and
  canonical extractors.
- `.standards` (modify) — advances the standards submodule to require one producer-owned canonical data authority for
  internal data contracts.
- `components/phase/resources/packs/miniforge-standards.pack.edn` (modify) — recompiles the bundled dogfood policy pack.
- `docs/pull-requests/2026-05-22-replace-verbose-full-result-pprint-with-compact-operator-summary-in.md` (create) — this
  PR doc.

## Test Results

- `bb pre-commit` — passed (314 smoke tests / 1142 assertions; GraalVM compatibility 6 tests / 510 assertions).

## Review Decision

_No review artifacts available._
