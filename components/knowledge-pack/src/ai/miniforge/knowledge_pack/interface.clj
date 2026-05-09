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

   Two strata:
     Layer 0 — schema re-exports.
     Layer 1 — operation re-exports."
  (:require
   [ai.miniforge.knowledge-pack.pack   :as pack-impl]
   [ai.miniforge.knowledge-pack.schema :as schema-impl]
   [ai.miniforge.knowledge-pack.verify :as verify-impl]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports.

(def ZettelRef     schema-impl/ZettelRef)
(def KnowledgePack schema-impl/KnowledgePack)

;------------------------------------------------------------------------------ Layer 1
;; Operation re-exports.

(def build-pack             pack-impl/build-pack)
(def update-pack            pack-impl/update-pack)
(def add-zettel             pack-impl/add-zettel)
(def remove-zettel          pack-impl/remove-zettel)
(def zettel->ref            pack-impl/zettel->ref)
(def compute-digest         pack-impl/compute-digest)
(def revision-id-from-digest pack-impl/revision-id-from-digest)
(def verify-pack            verify-impl/verify-pack)
