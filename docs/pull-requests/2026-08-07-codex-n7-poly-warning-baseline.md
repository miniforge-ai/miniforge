# fix: clear the Polylith warning baseline

## Overview

Removes the four repository-wide `poly check` warnings discovered while
validating the N7 grant contracts.

## Motivation

The N7 implementation workflow requires `poly check` to report zero errors and
warnings. A stale development path, a misplaced config test fixture, and four
unnecessary project dependencies made that criterion impossible even for
otherwise clean changes.

## Changes in Detail

- Remove the stale Codex gap test-resource path.
- Move config resource fixtures to a Polylith-compatible test-resource path.
- Remove the Data Foundry dependencies on `decision-envelope`,
  `effect-transaction`, and `execution-grant` that Poly proves are unused.
- Remove the Miniforge Core dependency on `codex-gap` that Poly proves is
  unused.
- Correct stale Polylith theme text and malformed TUI work-spec indentation.

## Testing Plan

- `clojure -M:poly check` — `OK` with zero warnings and errors.
- `clojure -M:poly test brick:config :dev` — passed in all project contexts.
- `clojure -M:poly test project:data-foundry:miniforge-core` — passed.
- Review each dependency removal against project and base source usage.

## Deployment Plan

Merge to `main`; no runtime behavior or persisted data changes.

## Related Issues/PRs

- PR #1697 — N7 grant issuance contract reconciliation

## Checklist

- [x] Poly reports zero errors and warnings
- [x] Config resource tests pass from their new location
- [x] Removed dependencies have no project or base source usage
- [x] Pre-commit and adversarial review pass
