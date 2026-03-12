# PR: Clean up public workflow copy in shared surfaces

## Summary

This PR removes software-factory-specific workflow examples from the live shared
CLI help and kernel workflow catalog copy.

The goal is not to rename the whole product in one pass. It is to stop the
shared public surfaces from implying that SDLC workflows are the only first
class workflows.

## Changes

- Updated CLI help examples in `ai.miniforge.cli.main`
- Updated generic chain docstrings in:
  - `ai.miniforge.cli.workflow-runner`
  - `ai.miniforge.workflow.chain-loader`
- Rewrote `components/workflow/resources/workflows/README.md` so it describes
  the workflows that actually ship in the shared workflow component today
- Added a regression test for CLI help output

## Why

After moving workflow families, phase implementations, chains, defaults, and
recommendation prompt vocabulary behind app composition, the remaining leak on
the public path was copy.

The live help output and kernel workflow catalog still pointed users at
software-factory examples that no longer belong to the shared workflow
component.

## Test coverage

New coverage in:

- `ai.miniforge.cli.main-test`
  - generic workflow examples appear in help output
  - software-factory workflow ids are not shown in shared help output

## Verification

- `clojure -M:dev:test -e "(require 'ai.miniforge.cli.main-test) ..."`
- `bb test`
- `bb build:cli`
- `bb pre-commit`

## Follow-up

The next seam after this is the deeper software-factory terminology embedded in
internal workflow component docstrings and older architecture docs, which is a
larger documentation pass rather than a live-surface cleanup.
