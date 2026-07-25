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
(ns ai.miniforge.knowledge-pack.pack
  "Knowledge pack operations: build, update, project to a content-
   addressed reference triple, derive the manifest digest +
   revision-id.

   Six strata (over the rule-210 budget of 3 — a namespace-split
   candidate, not resolved here): field lists and pure derivations
   at Layer 0, rising through the projection/digest/stamp chain to
   the public constructors (`build-pack`, `update-pack`) at Layer 4
   and the manifest mutators (`add-zettel`, `remove-zettel`) at
   Layer 5, which call back into `update-pack`."
  (:require
   [ai.miniforge.content-hash.interface :as content-hash])
  (:import
   [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

;; Content-bearing subset + revision identity.
(def ^{:stratum 0} ^:private content-bearing-fields
  "Fields whose value affects a pack's revision identity. Operational
   metadata (`:pack/id`, `:pack/created`, `:pack/modified`,
   `:pack/author`, `:fleet/*`, `:privacy/*`) is excluded — same
   shape as zettel's content-bearing-fields so a privacy
   reclassification or oss-version bump does NOT silently mint a
   new revision."
  [:pack/uid
   :pack/title
   :pack/version
   :pack/description
   :pack/zettels
   :pack/dependencies])

(defn ^{:stratum 0} revision-id-from-digest
  "Pure: derive a stable UUID from a digest hex string. Two packs
   with identical content-projections land on the same
   `:pack/revision-id`."
  ^UUID [^String digest]
  (UUID/nameUUIDFromBytes (.getBytes digest "UTF-8")))

(def ^{:stratum 0} ^:private derived-fields
  "Fields the constructor / updater own — callers cannot override
   them via `update-pack`. Ensures the relationship between content
   and revision identity stays tamper-evident, mirroring the
   zettel-side spoof guard."
  #{:pack/digest :pack/revision-id})

;; Public constructors + projection.
(defn ^{:stratum 0} zettel->ref
  "Pure: project a zettel map down to the `(zettel/id,
   zettel/revision-id, zettel/digest)` triple a pack manifest holds.

   Throws `ex-info` if any of the three fields is missing — packs
   MUST reference content-addressable zettels per Decision 6, and
   the constructor refuses to silently emit a malformed triple. A
   legacy zettel without `:zettel/revision-id` / `:zettel/digest`
   should be passed through `knowledge.zettel/update-zettel` first
   to backfill those fields, after which the projection succeeds."
  [zettel]
  (let [{:zettel/keys [id revision-id digest]} zettel]
    (when (or (nil? id) (nil? revision-id) (nil? digest))
      (throw (ex-info "knowledge-pack: zettel missing one of [:zettel/id :zettel/revision-id :zettel/digest] — pack manifests require revision-keyed references"
                      {:zettel/id          id
                       :zettel/revision-id revision-id
                       :zettel/digest      digest})))
    {:zettel/id          id
     :zettel/revision-id revision-id
     :zettel/digest      digest}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} content-projection
  "Pure: project a pack down to the content-bearing subset that feeds
   the digest. Stable across additions of operational metadata."
  [pack]
  (select-keys pack content-bearing-fields))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} compute-digest
  "Pure: SHA-256 hex of the pack's canonical-EDN content-projection.
   Stable across map-key reorderings and across non-content metadata
   changes; rotates whenever a content-bearing field changes."
  [pack]
  (content-hash/content-hash (content-projection pack)))

;------------------------------------------------------------------------------ Layer 3

(defn- ^{:stratum 3} stamp-revision
  "Pure: stamp a pack with `:pack/digest` + `:pack/revision-id`
   derived from its current content projection. Idempotent for an
   already-stamped pack whose content has not changed."
  [pack]
  (let [d (compute-digest pack)]
    (assoc pack
           :pack/digest      d
           :pack/revision-id (revision-id-from-digest d))))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} build-pack
  "Construct a fresh content-addressed pack manifest.

   Required arguments:
   - uid     — human-readable pack id (e.g. \"rds-import-runbook\").
   - title   — short display name.
   - version — opaque version string (semver / dateversion / etc.).
   - zettels — sequence of zettel maps (NOT triples). Each is
                projected via `zettel->ref` so callers can pass the
                same map shape they get from `knowledge/get-zettel`.

   Options:
   - :description           — markdown body.
   - :author                — string (default: \"user\").
   - :dependencies          — vector of `(pack-id, pack-revision-id,
                              pack-digest)` triples.
   - :fleet/shareable       — boolean.
   - :fleet/share-scope     — :org / :team / :repo / :workflow.
   - :privacy/classification — :public-org / :internal / :restricted /
                               :secret.
   - :fleet/oss-version     — version string.

   The constructor stamps `:pack/digest` + `:pack/revision-id`
   automatically, so two packs built from identical content land on
   the same revision id.

   Example:
     (build-pack \"rds-import-runbook\" \"RDS Import Runbook\" \"1.0.0\"
                 [zettel-1 zettel-2]
                 :description \"...\"
                 :fleet/share-scope :org
                 :privacy/classification :internal)"
  [uid title version zettels
   & {:keys [description author dependencies
             fleet/shareable fleet/share-scope privacy/classification
             fleet/oss-version]
      :or   {author "user"}}]
  (let [now  (java.util.Date.)
        pack (cond-> {:pack/id      (random-uuid)
                      :pack/uid     uid
                      :pack/title   title
                      :pack/version version
                      :pack/zettels (mapv zettel->ref zettels)
                      :pack/created now
                      :pack/author  author}
               description    (assoc :pack/description description)
               (seq dependencies)
               (assoc :pack/dependencies (vec dependencies))
               (some? shareable)      (assoc :fleet/shareable shareable)
               share-scope            (assoc :fleet/share-scope share-scope)
               classification         (assoc :privacy/classification classification)
               oss-version            (assoc :fleet/oss-version oss-version))]
    (stamp-revision pack)))

(defn ^{:stratum 4} update-pack
  "Update a pack with new values, setting the modified timestamp.

   `:pack/digest` and `:pack/revision-id` are DERIVED — any value
   the caller supplies for either is dropped before the merge.
   `update-pack` always re-stamps the result via `stamp-revision`,
   which is idempotent on unchanged content (same content → same
   digest → same revision-id) and rotates the revision when a
   content-bearing field changes (`:pack/uid` / `:pack/title` /
   `:pack/version` / `:pack/description` / `:pack/zettels` /
   `:pack/dependencies`).

   Operational-metadata-only changes (`:fleet/*` / `:privacy/*` /
   author) leave the revision identity intact, mirroring zettel
   semantics — Decision 6 attaches trust to the immutable pack
   revision, so changing a privacy classification must NOT silently
   migrate trust onto a new revision.

   A legacy pack without `:pack/digest` / `:pack/revision-id`
   receives the stamped fields on its first update."
  [pack changes]
  (let [sanitised (apply dissoc changes derived-fields)
        merged    (-> pack
                      (merge sanitised)
                      (assoc :pack/modified (java.util.Date.)))]
    (stamp-revision merged)))

;------------------------------------------------------------------------------ Layer 5

(defn ^{:stratum 5} add-zettel
  "Add a zettel reference to a pack's manifest. The zettel is
   projected via `zettel->ref` (so callers can pass the zettel map
   directly) and the pack revision rotates via `update-pack`."
  [pack zettel]
  (let [ref      (zettel->ref zettel)
        existing (vec (:pack/zettels pack))]
    (update-pack pack {:pack/zettels (conj existing ref)})))

(defn ^{:stratum 5} remove-zettel
  "Remove every reference to `zettel-id` from the pack's manifest.
   Pack revision rotates if any reference was actually removed."
  [pack zettel-id]
  (let [filtered (vec (remove #(= zettel-id (:zettel/id %))
                              (:pack/zettels pack)))]
    (update-pack pack {:pack/zettels filtered})))
