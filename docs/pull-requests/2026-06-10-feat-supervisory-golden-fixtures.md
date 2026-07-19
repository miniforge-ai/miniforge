<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat-supervisory-golden-fixtures

**Tier:** contract-hardening / consumer-enabler
**Theme:** supervisory control plane (N3 §3.19 / N5-delta-1 §3)

## Problem

The supervisory entity contract between this repo (Clojure producer) and
miniforge-control (Rust consumer) is maintained by convention only. The Rust
`supervisory-entities` crate hand-mirrors the Clojure schema with serde rename
attributes; its round-trip tests run against fixtures hand-written in Rust —
the author's understanding of the wire shape, not the wire shape. There is no
schema version on any upsert event, and serde silently drops unknown fields.
When the producer adds or renames an entity field (it already happened once:
the BD-1 `workflow-run/spec` payload), the consumer loses data silently.

This is the F2 finding from the 2026-06-10 miniforge-control design review:
the weakest seam in the control plane, and the same silent-downgrade class the
core review flagged elsewhere.

## Changes

### GROUP 1 — event-stream: expose the canonical wire encoder

`sinks.clj`: `write-transit-json` (private) becomes `event->transit-json`
(public). Same body, same verbose-mode transit writer the file sink persists
with. `interface.clj` re-exports it as `serialize-event` for contract tooling.
Rationale: golden fixtures must be produced by the exact production byte path,
or they certify nothing.

### GROUP 2 — supervisory-state: contract version stamp

`schema.clj`: new `schema-version` def (currently `1`) with bump rules in the
docstring — bump on any externally observable shape change; new OPTIONAL keys
do not require a bump (entities are open maps, consumers preserve unknowns).

`emitter.clj`: new private `attach-entity` helper assocs both
`:supervisory/entity` and `:supervisory/schema-version` onto every upsert
envelope; all nine constructors route through it, so a future entity family
cannot forget the stamp.

### GROUP 3 — supervisory-state: golden fixture generator

New `golden_fixtures.clj`: one pinned, schema-validated, obviously-synthetic
entity per emitted family (workflow-run, spec, agent, pr, policy-eval,
attention, task-node, decision, intervention), built into a real upsert event
via the production emitter constructors, envelope nondeterminism pinned
(fixed UUIDs, fixed instant, sequence 1), serialized via
`event-stream/serialize-event`, plus a `manifest.edn` carrying the schema
version and family list. Fails closed: an entity that does not validate
against its Malli schema aborts generation.

Worktree and AutomationEdge families are deliberately absent — they originate
in the miniforge-control Rust adapters (N5-delta-3); fixtures live with their
producer.

`interface.clj` exposes `schema-version` and `write-golden-fixtures!`
(`clojure -X` compatible).

### GROUP 4 — tests: the producer-side contract gate

`golden_fixtures_test.clj`, three gates:

1. **Coverage + determinism** — every family present, regeneration
   byte-identical.
2. **Round-trip** — each fixture re-reads through transit; event carries the
   contract version; entity validates against its schema.
3. **Drift gate** — committed `contracts/supervisory-entities/golden/` must
   equal a fresh generation. Any entity-shape change now fails this test with
   a regenerate-and-re-vendor instruction instead of silently breaking the
   Rust consumer.

### GROUP 5 — committed fixtures + bb task

`contracts/supervisory-entities/golden/` — the nine generated
`*.transit.json` files plus `manifest.edn`, committed as the canonical
contract corpus for downstream vendoring.

`bb.edn` — `fixtures:supervisory` task regenerates them.

## Consumer follow-up (miniforge-control)

Companion PR vendors this corpus into the `supervisory-entities` crate,
replaces/augments the hand-written fixtures with it, pins
`SCHEMA_VERSION = 1`, and warns at runtime on version mismatch. Tracked as F2
in the fix program.

## Verification

- `clojure -X:dev ...write-golden-fixtures!` + `bb fixtures:supervisory` both
  produce identical committed output (deterministic through both entry paths).
- Full supervisory-state + event-stream suites: 383 tests, 1777 assertions,
  0 failures (includes the 3 new contract gates, 41 assertions).
- clj-kondo on all touched files: 0 errors, 0 warnings.
