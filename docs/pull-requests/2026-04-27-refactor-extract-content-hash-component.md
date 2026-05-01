<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor: extract content-hash into standalone component

## Overview

Extracts content-hashing logic out of `evidence-bundle` and into its own standalone Polylith component,
`ai.miniforge.content-hash`, so other miniforge components and downstream miniforge-family repos can consume content
hashing without pulling all of evidence-bundle's SDLC schema.

## Motivation

Hashing artifact content is genuinely domain-free infrastructure — SHA-256 of canonical EDN. Today it lives inside
`components/evidence-bundle/src/ai/miniforge/evidence_bundle/hash.clj`, which couples it to evidence-bundle's
workflow/SDLC shape. Downstream consumers that want content hashing (thesium-workflows for inference-evidence,
miniforge-fleet for its evidence variant, future RAG audit records) shouldn't need evidence-bundle's full surface.

This refactor honors the rule that miniforge-family repos consume cross-cutting primitives **from miniforge OSS itself**
rather than from a separate commons repo: lift the primitive to a top-level component; keep the SDLC-specific
evidence-bundle schema where it belongs.

## Base Branch

`main`

## Depends On

None. Independent of the parallel `feat/anomaly-component` PR.

## Layer

Foundations / cross-cutting primitive. New top-level component; existing component refactored to depend on it.

## What This Adds / Changes

**New component** `components/content-hash/`:

- `deps.edn`
- `src/ai/miniforge/content_hash/core.clj` — implementation (canonical EDN serializer + SHA-256)
- `src/ai/miniforge/content_hash/interface.clj` — public API
- Six decomposed test files under `test/ai/miniforge/content_hash/interface/`:
  - `sha256_output_test.clj`
  - `canonical_edn_determinism_test.clj`
  - `content_hash_determinism_test.clj`
  - `content_hash_change_sensitivity_test.clj`
  - `round_trip_test.clj`
  - `edge_cases_test.clj`

**Modified `evidence-bundle`:**

- `components/evidence-bundle/deps.edn` — adds `ai.miniforge/content-hash` `:local/root` dep
- `components/evidence-bundle/src/ai/miniforge/evidence_bundle/interface.clj` — `:require` swapped to
  `content-hash.interface`; public `content-hash` delegates there. **Public API of evidence-bundle is unchanged.**
- `components/evidence-bundle/src/ai/miniforge/evidence_bundle/collector.clj` — `:require` swapped; same call sites
- `components/evidence-bundle/test/ai/miniforge/evidence_bundle/assembly_integration_test.clj` — `:as hash` alias
  retargeted at `content-hash.interface`
- **Deleted:** `components/evidence-bundle/src/ai/miniforge/evidence_bundle/hash.clj`

**Project-level wiring:**

- Root `deps.edn` (`:dev`, `:test`, `:conformance` aliases) — register `components/content-hash` paths and `:local/root`
  dep next to `algorithms`
- `projects/miniforge/deps.edn`, `projects/miniforge-core/deps.edn`, `projects/miniforge-tui/deps.edn` — same
  registration in each consumer project

## Public API of new component

```clojure
(content-hash x)    ; SHA-256 hex (64-char) of canonical EDN of x
(canonical-edn x)   ; deterministic EDN string with sorted map keys
```

## Behavioral note — hashing algorithm refinement

The new `content-hash` uses **canonical-EDN serialization (sorted map keys)** instead of the previous `pr-str`-based
approach.

**This is strictly more correct.** The old implementation produced different hashes for logically-equal hash maps with
  differing internal insertion order — a latent bug for maps larger than eight entries (where Clojure's hash-map
  representation transitions and may reorder).

No existing test pinned specific concrete hash values, so all `evidence-bundle` tests continue to pass unchanged.
Persisted evidence bundles produced under the old hashing will compute different hashes if recomputed, but no in-tree
consumer relies on hash stability across versions.

## Strata Affected

- `ai.miniforge.content-hash.interface` — public API
- `ai.miniforge.content-hash.core` — implementation
- `ai.miniforge.evidence-bundle.interface` — now delegates to content-hash
- `ai.miniforge.evidence-bundle.collector` — now imports content-hash

## Testing Plan

- `bb test` (changed bricks): **95 tests / 253 assertions / 0 failures / 0 errors** — covers all six new content-hash
  test namespaces and all existing evidence-bundle tests.
- New content-hash tests in isolation: 31 tests / 58 assertions / 0 failures.
- Evidence-bundle hash + assembly tests: 27 tests / 75 assertions / 0 failures.
- `bb compile` (full repo syntax check): **passed.**
- `bb pre-commit` (lint:clj + fmt:md + test + test:graalvm — repo equivalent of "gate"): **ALL PRE-COMMIT CHECKS
  PASSED.** Pre-commit hook runs cleanly on the second commit.

Coverage added:

- SHA-256 output is exactly 64 hex chars
- Canonical EDN: same logical map produces same string regardless of insertion order
- content-hash determinism across processes/JVMs
- Change-sensitivity: small data change → different hash
- Round-trip: `canonical-edn` → `read-string` is lossless
- Edge cases: nil, empty map, nested structures, keyword keys, mixed value types

## Deployment Plan

No migration required for **new** evidence bundles produced after this change.

For **persisted** evidence bundles whose content hashes were computed under the previous (insertion-order-dependent)
algorithm: hashes will differ if recomputed under the new algorithm. There are no in-tree consumers that pin expected
hash values, but downstream consumers that store and verify content hashes externally should be aware. This is captured
in the "Behavioral note" above.

Public API of `evidence-bundle` is unchanged — callers continue to call `evidence-bundle.interface/content-hash` and get
a SHA-256 hex string. The implementation moved; the contract did not.

## Notes / Deviations

- **`workspace.edn` not modified.** The project's `:necessary` lists hold workflow/runtime-loaded components only;
  standalone library components like `algorithms`/`content-hash` are auto-discovered by Polylith.
- **`bb gate` task does not exist** in this repo. Closest equivalent is `bb pre-commit`, which was run and passed.
- **Pre-existing `bb test:poly` errors** in `bb-dev-tools` (Polylith Error 101) and `miniforge-tui` (Error 107, missing
  `workflow-resume`) are present on main and unrelated to this change.
- **Verify helpers (`verify-content-hash`, `verify-evidence-bundle`, `valid?`)** from the old `hash.clj` were dropped
  along with the file. They were never exposed via `evidence-bundle/interface.clj` and had no in-repo callers, so this
  preserves evidence-bundle's public API.
- **Apache 2 headers + Layer-labeled headers** present on every new file; preserved on every modified file.

## Related Issues/PRs

- Companion `feat/anomaly-component` PR (sibling H-series component)
- Will enable `inference-evidence` work in thesium-workflows (downstream)
- Will enable Fleet-side evidence variants

## Checklist

- [x] New `content-hash` component with public API (`content-hash`, `canonical-edn`)
- [x] Decomposed test files (one per behavior, six total)
- [x] Apache 2 license header on every new file; preserved on every modified file
- [x] Layer-labeled comment headers
- [x] `evidence-bundle` updated to depend on new component
- [x] Old `hash.clj` deleted
- [x] Existing `evidence-bundle` tests unchanged and still passing
- [x] Project-level `deps.edn` registration in three consumer projects
- [x] `bb pre-commit` green
