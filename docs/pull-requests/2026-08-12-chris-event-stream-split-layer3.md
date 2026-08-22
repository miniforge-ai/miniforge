<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(event-stream): split core.clj to satisfy SL003

## Overview

Extracts three namespaces from `event-stream/core.clj` so no file exceeds the
three-layer stratified-design budget. No behaviour change.

## Motivation

`core.clj` is a latent SL003 violation: it uses four distinct layers against a
maximum of three. It was latent because the stratum linter runs on *staged*
files, and the file had not been staged since the rule tightened — a
whitespace-only edit is enough to surface it.

That makes it a blocker for any change to the file, which is how it was found:
a redaction change to `publish!` (N3 §8, following) could not be committed.

The autofix re-tags strata from actual dependencies. Doing so revealed nine
constructors tagged stratum 2 that in fact sit above `publish!` — the
mis-tagging is what had kept the file nominally at three layers.

## Changes in Detail

Three extractions, each driven by the layer count rather than by taste:

- **`compound-events`** — the chain and dependency constructors, plus the two
  private helpers (`dependency-event`, `dependency-id-string`) that became
  orphaned in `core` once their only callers moved. Moving them was preferable
  to widening `core`'s API for a cross-namespace call.
- **`phase-events`** — `phase-completed` with `phase-transition-request` and
  `redirect-target`. This cluster is three strata deep on its own, so sharing a
  namespace with the chain constructors pushed the file over budget again.
- **`transition-keys`** — the four phase-transition constants. Splitting these
  out is what brings `phase-events` to three layers. They are held as named
  constants rather than inlined at the use site (dewey 006).

`interface/events.clj` re-points at the new namespaces, so the public interface
is unchanged.

## Testing Plan

- Event-stream suites: 76 tests, 394 assertions, 0 failures — unchanged from
  before the split.
- `bb lint:stratum` clean; every file now reports three layers or fewer.
- `clojure -M:poly check` OK.
- No public interface changed, so callers outside the component are untouched.

## Deployment Plan

Pure refactor. No behaviour change, no schema change, no event change.

## Related Issues/PRs

- Prerequisite for the N3 §8 redaction change, which wires `redaction/redact`
  into `publish!` and cannot be committed while this file fails the hook.
- Same class as the in-flight `rule 210` splits.

## Checklist

- [x] Latent violation confirmed reproducible with a whitespace-only edit
- [x] Extractions driven by layer count, not taste
- [x] Orphaned private helpers moved rather than made public
- [x] Named constants preserved (006)
- [x] Public interface unchanged
- [x] Tests unchanged in count and all passing
- [x] Copyright headers present (810)
- [x] PR doc created (721)
