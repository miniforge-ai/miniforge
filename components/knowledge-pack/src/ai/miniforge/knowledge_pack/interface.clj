;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.knowledge-pack.interface
  "Public API for the knowledge-pack component (Zettelkasten
   distribution layer).

   Implements the pack half of miniforge-fleet's Phase E Decision 6:
   zettels are the wire format, packs are the curation /
   distribution layer, content-addressed end-to-end so trust
   attaches to immutable revisions.

   Public surface:
     `ZettelRef`   — Malli schema for the `(zettel/id,
                     zettel/revision-id, zettel/digest)` triple
                     packs use to reference zettel revisions.
     `KnowledgePack` — Malli schema for the manifest itself.
     `build-pack`  — fresh pack constructor; stamps `:pack/digest`
                     + `:pack/revision-id` automatically.
     `update-pack` — strips caller-supplied derived fields and
                     re-stamps; idempotent on unchanged content.
     `add-zettel`  — append a zettel reference; rotates revision.
     `remove-zettel` — remove every reference to a zettel-id; rotates.
     `zettel->ref` — pure projection from a zettel map to its
                     content-addressed reference triple.
     `compute-digest`        — pure SHA-256 hex of the pack's
                                content projection.
     `revision-id-from-digest` — pure UUID derivation from a digest.
     `verify-pack` — verify a pack against a zettel store. Returns
                     `{:valid? :pack/discrepancy :ref/discrepancies}`.

   One stratum: every export here is a re-export of a var from
   `pack`/`schema`/`verify` (schema re-exports, then operation
   re-exports); none reference each other within this file."
  (:require
   [ai.miniforge.knowledge-pack.pack   :as pack-impl]
   [ai.miniforge.knowledge-pack.schema :as schema-impl]
   [ai.miniforge.knowledge-pack.verify :as verify-impl]))

;------------------------------------------------------------------------------ Layer 0

;; Schema re-exports.
(def ^{:stratum 0} ZettelRef
  "Malli schema (`[:map {:closed false}]`) for the canonical
   Decision-6 content-addressed reference to a zettel revision: the
   `(:zettel/id, :zettel/revision-id, :zettel/digest)` triple. `id`
   and `revision-id` are `uuid?`; `digest` is a 64-char lowercase
   hex string. Pack manifests reference zettels only via this triple
   so trust attaches to an immutable revision, never the mutable
   logical id."
  schema-impl/ZettelRef)

(def ^{:stratum 0} KnowledgePack
  "Malli schema (`[:map {:closed false}]`) for a knowledge-pack
   manifest: a curated, content-addressed collection of zettel
   revisions. Required keys: `:pack/id` (uuid), `:pack/uid`,
   `:pack/title`, `:pack/version` (non-empty strings),
   `:pack/zettels` (vector of `ZettelRef`, may be empty),
   `:pack/created` (inst), `:pack/author` (string). Revision-keyed
   identity (`:pack/revision-id` uuid, `:pack/digest` hex) and
   Fleet-share fields (`:pack/description`, `:pack/dependencies`,
   `:fleet/shareable`, `:fleet/share-scope`,
   `:privacy/classification`, `:fleet/oss-version`, `:pack/modified`)
   are optional; the constructors stamp the derived identity fields."
  schema-impl/KnowledgePack)

;; Operation re-exports.
(def ^{:stratum 0} build-pack
  "Construct a fresh content-addressed pack manifest.
   `(build-pack uid title version zettels & opts)`. `uid` / `title` /
   `version` are strings; `zettels` is a sequence of zettel MAPS (not
   triples) — each is projected via `zettel->ref`. Optional kwargs:
   `:description`, `:author` (default \"user\"), `:dependencies`,
   `:fleet/shareable`, `:fleet/share-scope`, `:privacy/classification`,
   `:fleet/oss-version`. Returns a pack map with `:pack/digest` +
   `:pack/revision-id` stamped automatically. Throws (via
   `zettel->ref`) if any zettel lacks the required reference fields."
  pack-impl/build-pack)

(def ^{:stratum 0} update-pack
  "Apply `changes` to a pack and re-stamp its revision identity.
   `(update-pack pack changes)` -> updated pack map. Caller-supplied
   `:pack/digest` / `:pack/revision-id` in `changes` are dropped;
   `:pack/modified` is set; the result is re-stamped. Idempotent on
   unchanged content (same content -> same digest -> same
   revision-id); rotates the revision only when a content-bearing
   field changes (`:pack/uid` / `:pack/title` / `:pack/version` /
   `:pack/description` / `:pack/zettels` / `:pack/dependencies`)."
  pack-impl/update-pack)

(def ^{:stratum 0} add-zettel
  "Append a zettel reference to a pack's manifest.
   `(add-zettel pack zettel)` -> updated pack map. `zettel` is a
   zettel MAP (projected via `zettel->ref`); the pack revision
   rotates via `update-pack`. Throws if `zettel` lacks the required
   reference fields."
  pack-impl/add-zettel)

(def ^{:stratum 0} remove-zettel
  "Remove every reference to `zettel-id` from a pack's manifest.
   `(remove-zettel pack zettel-id)` -> updated pack map. The revision
   rotates only if a reference was actually removed."
  pack-impl/remove-zettel)

(def ^{:stratum 0} zettel->ref
  "Pure projection from a zettel map to its content-addressed
   reference triple. `(zettel->ref zettel)` -> `{:zettel/id
   :zettel/revision-id :zettel/digest}`. Throws `ex-info` if any of
   the three fields is missing (legacy zettels must be backfilled via
   `knowledge.zettel/update-zettel` first)."
  pack-impl/zettel->ref)

(def ^{:stratum 0} compute-digest
  "Pure: SHA-256 hex string of the pack's canonical-EDN content
   projection. `(compute-digest pack)` -> 64-char hex string. Stable
   across map-key reorderings and non-content metadata changes;
   rotates when a content-bearing field changes."
  pack-impl/compute-digest)

(def ^{:stratum 0} revision-id-from-digest
  "Pure: derive a stable UUID from a digest hex string.
   `(revision-id-from-digest digest)` -> `java.util.UUID`. Two packs
   with identical content projections land on the same revision-id."
  pack-impl/revision-id-from-digest)

(def ^{:stratum 0} verify-pack
  "Verify a pack against a zettel store.
   `(verify-pack pack lookup-fn)` where `lookup-fn` is
   `(fn [zettel-id revision-id] -> zettel-map | nil)`. Returns a map
   `{:valid? boolean, :pack/discrepancy (map | nil),
   :ref/discrepancies vector}`. `:valid?` is true iff the
   manifest-level discrepancy is nil AND the per-ref discrepancy
   vector is empty; per-ref problems are accumulated, not
   short-circuited."
  verify-impl/verify-pack)
