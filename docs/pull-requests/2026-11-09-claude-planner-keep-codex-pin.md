<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: The planner keeps the codex pin inline when it sheds file bodies

## Overview

When the planner's prompt is too large, it sheds every inlined file body
to a manifest of paths (N12 §6). The Thesium Codex pin was shed with them,
so the planner could run without ever seeing its consultation. The pin's
body now stays inlined on the shed path; only the other files drop to the
manifest.

## Motivation

The `:codex-consultation` gate only warns on a pinned consultation that was
never fetched (`:codex-pin-not-fetched`). The warning is informational on
the premise that the pin is in the prompt. On the planner's shed path that
premise was false: the pin became one manifest line, so an unfetched pin
meant an unread consultation, and nothing failed. Found while correcting
the codex atlas in #2406.

## Changes in Detail

- `phase_software_factory/codex_pin.clj`: `pin-outcome`'s entry carries
  `:keep-inline? true`.
- `agent/planner.clj`:
  - new `format-existing-files-shed` inlines `:keep-inline?` files and
    lists the rest in the manifest;
  - new `assemble-with-shed` runs one budget pass for a given shed
    renderer;
  - `assemble-within-budget` uses the new renderer for the shed path and
    counts only the other files as sheddable;
  - if the kept body alone still overflows the window, it falls back to
    the full manifest, so a pin can never fail a plan that used to fit;
  - `:file-count` still reports every existing file.
- Atlas panels 2 and 3 and *The Worry Book*: the pin stays in the prompt
  when other bodies are shed.

The implementer always inlines file bodies and ignores the flag.

## Testing Plan

- `planner_test.clj` `assemble-within-budget-test`:
  - on the shed path, the pin's body stays and the other body is gone;
  - a pin that overflows the window alone drops to the manifest, and the
    plan still fits.
- `codex_pin_test.clj`: `pin-outcome`'s entry carries `:keep-inline? true`.
- Locally only the Clojure reader and a standalone evaluation of
  `format-existing-files-shed` ran; the full suite runs in CI.

## Deployment Plan

No configuration or migration. Planner prompts on the shed path now include
the pin body, so they are larger by the pin's size.

## Related Issues/PRs

- #2406: the codex atlas, which documented the gap this closes.

## Checklist

- [x] Behavior and tests in the same PR (dewey 716)
- [x] Atlas text updated to match the new behavior
