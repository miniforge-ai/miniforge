# refactor: exceptions-as-data cleanup of event-stream

## Overview

Kills the explicit antipattern in
`components/event-stream/src/.../listeners.clj` (5 sites) and
`components/event-stream/src/.../sinks.clj` (3 sites): each throw was
*already constructing an anomaly map* via `response/make-anomaly`, then
wrapping it inside `(throw (ex-info ... {:anomaly <anomaly-map>}))`.
The cleanup collapses these to a single canonical
`response/throw-anomaly!` call so the anomaly is the data, not a stowaway
inside `ex-data`'s `:anomaly` key.

## Motivation

Per `work/exception-cleanup-inventory.md`, `event-stream` had 8
`:cleanup-needed` sites. The audit specifically called out the
listeners.clj anti-pattern (anomaly construction wrapped inside a
manual ex-info throw) as the strongest justification for this PR. The
shape is what the exceptions-as-data migration exists to retire: the
data is already there, the throw was just glue.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged) — anomaly type vocabulary
- `ai.miniforge.response` (merged) — slingshot `throw-anomaly!`

## Layer

Refactor / per-component cleanup tier.

## What This Adds / Changes

`components/event-stream/deps.edn`:

- Adds `:local/root` deps on `ai.miniforge/anomaly` and
  `ai.miniforge/response`. `response` was already required by
  `listeners.clj` but missing from the component's own `deps.edn`
  (pre-existing latent gap; the implicit polylith workspace was
  resolving it).

`components/event-stream/src/.../listeners.clj`:

- 5 throw sites collapsed. Each pre-cleanup site was
  `(throw (ex-info "..." {:anomaly (response/make-anomaly <cat>
  <msg> <data>)}))`; each post-cleanup site is
  `(response/throw-anomaly! <cat> <msg> <data>)`.
- `register-listener!` — invalid capability →
  `:anomalies/incorrect`.
- `submit-annotation!` — listener not found → `:anomalies/not-found`;
  insufficient capability → `:anomalies/forbidden`.
- `submit-control-action!` — listener not found →
  `:anomalies/not-found`; insufficient capability →
  `:anomalies/forbidden`.

`components/event-stream/src/.../sinks.clj`:

- `fleet-sink` — missing `:url` → `:anomalies/incorrect` (was a bare
  `(throw (ex-info ...))` inside an `or` form).
- `create-sink` — unknown sink type → `:anomalies/unsupported`;
  non-map non-vector config → `:anomalies/incorrect`.

`components/event-stream/test/.../anomaly/` (new):

- `listeners_anomaly_test.clj` — 6 tests covering the three
  listeners.clj escalation paths and the ex-data shape carried by each.
- `sinks_anomaly_test.clj` — 5 tests covering the three sinks.clj
  escalation paths and the ex-data shape.

## Per-site classification

| Site (line) | Fn | Anomaly category | Rationale |
|------------:|----|------------------|-----------|
| listeners.clj:109 | `register-listener!` (invalid capability) | `:anomalies/incorrect` | caller-supplied capability not in valid set |
| listeners.clj:191 | `submit-annotation!` (listener not found) | `:anomalies/not-found` | caller-supplied listener-id does not resolve |
| listeners.clj:197 | `submit-annotation!` (insufficient capability) | `:anomalies/forbidden` | listener's capability below `:advise` |
| listeners.clj:229 | `submit-control-action!` (listener not found) | `:anomalies/not-found` | caller-supplied listener-id does not resolve |
| listeners.clj:235 | `submit-control-action!` (insufficient capability) | `:anomalies/forbidden` | listener's capability below `:control` |
| sinks.clj:294 | `fleet-sink` (missing :url) | `:anomalies/incorrect` | caller-supplied opts missing required key |
| sinks.clj:376 | `create-sink` (unknown sink type) | `:anomalies/unsupported` | caller-supplied `:type` not in dispatch table |
| sinks.clj:383 | `create-sink` (invalid configuration) | `:anomalies/incorrect` | caller-supplied config is neither map nor vector nor keyword shortcut |

## Strata Affected

- `ai.miniforge.event-stream.listeners` — 5 anti-pattern throws
  collapsed
- `ai.miniforge.event-stream.sinks` — 3 raw `(throw (ex-info ...))`
  sites migrated
- New `ai.miniforge.event-stream.anomaly.*` test namespaces

## Testing Plan

- New `anomaly.*` test files: 11 tests, all green via cognitect
  test-runner.
- Existing `event-stream` test suite continues to pass — pre-cleanup
  tests using `(thrown? Exception ...)` and `(thrown-with-msg?
  ExceptionInfo #"...")` still match because `response/throw-anomaly!`
  raises `ExceptionInfo` with the canonical message preserved.

## Deployment Plan

No migration. External slingshot callers continue to see the same
`ExceptionInfo` shape, with the anomaly now top-level in `ex-data`
(category, message, context) rather than buried under an `:anomaly` key.

## Notes

- **event-stream/deps.edn was missing an explicit dep on
  `ai.miniforge/response`**, despite `listeners.clj` requiring it.
  Added explicitly. Pre-existing latent gap, surfaced (not caused) by
  this refactor.
- **`requiring-resolve` smell in `submit-control-action!`** noted but
  left as-is — out of scope per the "no drive-by refactors" rule.
- The 2 hits in `event_stream/snowflake.clj` flagged by the audit
  classify as `:fatal-only` (UUID-shape invariants); not migrated.

## Related Issues/PRs

- Built on PR #777 (kill-the-deprecation precedent)
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)
- Companion to Wave 7 + Wave 8 cleanup PRs — pr-lifecycle (#846)

## Checklist

- [x] All 5 listeners.clj antipattern sites collapsed
- [x] All 3 sinks.clj raw throws migrated
- [x] Single API per site (`response/throw-anomaly!`)
- [x] Decomposed test files (two)
- [x] No new throws in anomaly-returning code paths
- [x] External caller contracts preserved (`ExceptionInfo`-matching
      tests still pass)
- [x] Apache 2 license headers preserved
- [x] `deps.edn` gap (missing `response` declaration) fixed
