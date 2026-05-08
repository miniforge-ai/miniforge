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

(ns ai.miniforge.knowledge-pack.schema
  "Malli schemas for Zettelkasten knowledge packs.

   miniforge-fleet's Phase E Decision 6 makes packs the curation +
   distribution layer for zettels. Two invariants the schema pins:

     1. **Content-addressed references**, never 'latest in pack'
        semantics. Each entry in `:pack/zettels` is an exact
        `(zettel/id, zettel/revision-id, zettel/digest)` triple. A
        pack manifest that loaded would have surfaced a different
        revision or a tampered digest fails verification at the
        boundary, never silently propagates.

     2. **Revision-keyed identity** (mirroring the zettel pattern
        landed in PR #807). Trust attaches to `:pack/revision-id`,
        not to the mutable `:pack/id`. A change to the pack manifest
        (adding / removing a zettel triple, retitling, etc.)
        produces a new revision that does NOT inherit the prior
        revision's trust automatically.

   Schemas are layered:
     Layer 0 — `ZettelRef`: the canonical content-addressed triple
               packs and other content-addressed surfaces use to
               reference a specific zettel revision.
     Layer 1 — `KnowledgePack`: the manifest itself, plus its
               revision-keyed identity + Fleet-share fields."
  (:require
   [ai.miniforge.knowledge.interface :as knowledge]))

;------------------------------------------------------------------------------ Layer 0
;; Content-addressed reference triple.

(def ZettelRef
  "The canonical Decision-6 reference to a zettel revision. Pack
   manifests + future content-addressed surfaces reference zettels
   only via this triple; no surface is permitted to substitute the
   logical `:zettel/id` for a revision-keyed reference, since that
   would silently re-target trust on every content edit."
  [:map {:closed false}
   [:zettel/id          uuid?]
   [:zettel/revision-id uuid?]
   [:zettel/digest      [:re #"^[0-9a-f]{64}$"]]])

;------------------------------------------------------------------------------ Layer 1
;; Pack manifest.

(def KnowledgePack
  "A curated collection of zettel revisions distributed as a
   content-addressed manifest.

   Required fields:
     `:pack/id`            — UUID. Stable logical identity across
                              pack revisions.
     `:pack/uid`           — human-readable id (e.g. \"rds-import-runbook\").
     `:pack/title`         — short display name.
     `:pack/version`       — opaque version string. Pack authors
                              choose semver / dateversion / whatever
                              suits their distribution cadence;
                              the schema only requires non-empty.
     `:pack/zettels`       — vector of `ZettelRef` triples. The
                              schema permits an empty pack so
                              authors can stage a manifest before
                              populating it.
     `:pack/created`       — inst.
     `:pack/author`        — string.

   Revision-keyed identity (mirrors zettel; populated by
   `knowledge-pack/build-pack` + rotated by
   `knowledge-pack/update-pack`):
     `:pack/revision-id`   — UUID derived from the digest.
     `:pack/digest`        — SHA-256 hex of canonical-EDN of the
                              pack's content-bearing subset.

   Fleet-share intent (Decision 8 + 13 + 14; populated when the
   pack is destined for the Fleet event log):
     `:pack/description`           — markdown body.
     `:pack/dependencies`          — vector of `(pack-id,
                                      pack-revision-id, pack-digest)`
                                      triples this pack composes.
     `:fleet/shareable`            — boolean.
     `:fleet/share-scope`          — `:org` / `:team` / `:repo` /
                                      `:workflow`.
     `:privacy/classification`     — `:public-org` / `:internal` /
                                      `:restricted` / `:secret`.
     `:fleet/oss-version`          — string.
     `:pack/modified`              — inst, stamped on update."
  [:map {:closed false}
   ;; Identity.
   [:pack/id      uuid?]
   [:pack/uid     [:string {:min 1}]]
   [:pack/title   [:string {:min 1 :max 200}]]
   [:pack/version [:string {:min 1}]]

   ;; Manifest body.
   [:pack/zettels [:vector ZettelRef]]

   ;; Operational metadata.
   [:pack/created  inst?]
   [:pack/author   [:string {:min 1}]]
   [:pack/modified {:optional true} inst?]

   ;; Optional content-bearing extras.
   [:pack/description  {:optional true} :string]
   [:pack/dependencies {:optional true}
    [:vector [:map
              [:pack/id          uuid?]
              [:pack/revision-id uuid?]
              [:pack/digest      [:re #"^[0-9a-f]{64}$"]]]]]

   ;; Revision-keyed identity (Decision 6). Optional in the schema
   ;; so legacy packs round-trip; new packs stamp these via
   ;; `knowledge-pack/build-pack` and rotate via
   ;; `knowledge-pack/update-pack`.
   [:pack/revision-id {:optional true} uuid?]
   [:pack/digest      {:optional true} [:re #"^[0-9a-f]{64}$"]]

   ;; Fleet-share intent.
   [:fleet/shareable        {:optional true} boolean?]
   [:fleet/share-scope      {:optional true} knowledge/ShareScope]
   [:privacy/classification {:optional true} knowledge/Classification]
   [:fleet/oss-version      {:optional true} [:string {:min 1}]]])
