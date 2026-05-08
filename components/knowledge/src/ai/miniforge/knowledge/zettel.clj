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

(ns ai.miniforge.knowledge.zettel
  "Zettel (atomic note) operations.
   Layer 0: Zettel creation and manipulation
   Layer 1: Link management
   Layer 2: Serialization (Markdown with YAML frontmatter)"
  (:require
   [ai.miniforge.content-hash.interface :as content-hash]
   [ai.miniforge.knowledge.schema :as schema]
   [clojure.string :as str]
   [clj-yaml.core :as yaml]
   [malli.core :as m])
  (:import
   [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0
;; Content-bearing subset + revision-id derivation.
;;
;; miniforge-fleet's Phase E Decision 6: trust attaches to the IMMUTABLE
;; revision of a zettel, not to its mutable logical id. To make that
;; mechanical we (a) define the closed set of fields that are "the
;; content" — anything that affects WHAT the zettel says — and (b)
;; derive `:zettel/revision-id` deterministically from the digest so two
;; agents producing the same content land on the same revision id.

(def ^:private content-bearing-fields
  "Fields whose value affects the zettel's revision identity. Operational
   metadata (`:zettel/id`, `:zettel/created`, `:zettel/modified`,
   `:zettel/author`, `:zettel/backlinks`, `:fleet/*`, `:privacy/*`,
   `:fleet/oss-version`) is intentionally excluded — they describe
   surrounding policy, not knowledge content."
  [:zettel/uid
   :zettel/title
   :zettel/content
   :zettel/type
   :zettel/dewey
   :zettel/tags
   :zettel/links
   :zettel/source])

(defn- content-projection
  "Pure: project `zettel` down to the content-bearing subset that feeds
   the digest. Stable across additions of operational metadata."
  [zettel]
  (select-keys zettel content-bearing-fields))

(defn compute-digest
  "Pure: SHA-256 hex digest of the zettel's canonical-EDN
   content-projection. Stable across map-key reorderings and across
   non-content metadata changes; rotates whenever a content-bearing
   field changes. Used to seed `:zettel/digest` and (deterministically)
   `:zettel/revision-id`."
  [zettel]
  (content-hash/content-hash (content-projection zettel)))

(defn revision-id-from-digest
  "Pure: derive a stable UUID from a digest hex string. Two zettels with
   identical content land on the same `:zettel/revision-id` — the
   property miniforge-fleet's E.1 idempotent-retry path needs."
  ^UUID [^String digest]
  (UUID/nameUUIDFromBytes (.getBytes digest "UTF-8")))

(defn- stamp-revision
  "Pure: stamp a zettel with `:zettel/digest` + `:zettel/revision-id`
   derived from its current content projection. Idempotent for an
   already-stamped zettel whose content has not changed."
  [zettel]
  (let [d (compute-digest zettel)]
    (assoc zettel
           :zettel/digest d
           :zettel/revision-id (revision-id-from-digest d))))

(defn- content-changed?
  "Pure: true when `changes` touches any content-bearing field. Used by
   `update-zettel` to decide whether to rotate the revision."
  [changes]
  (some #(contains? changes %) content-bearing-fields))

;------------------------------------------------------------------------------ Layer 1
;; Zettel creation and manipulation

(defn create-zettel
  "Create a new zettel with required fields.

   Arguments:
   - uid     - Human-readable unique identifier (e.g., '210-clojure-ns')
   - title   - Short descriptive title
   - content - Markdown content body
   - type    - Zettel type keyword

   Options (local Zettelkasten):
   - :dewey  - Dewey classification code (e.g., \"210\")
   - :tags   - Vector of keyword tags
   - :links  - Vector of Link maps
   - :source - Source/provenance map
   - :author - Author string (default: 'user')

   Options (miniforge-fleet share intent):
   - :fleet/shareable        — boolean. Producer's intent to share
                               via the Fleet event log.
   - :fleet/share-scope      — `:org` / `:team` / `:repo` / `:workflow`.
   - :privacy/classification — `:public-org` / `:internal` /
                               `:restricted` / `:secret`.
   - :fleet/oss-version      — version string the zettel was captured
                               against. Stamped onto every event the
                               agent runtime emits; quarantine + migration
                               registry key off this on the Fleet side.

   The constructor stamps `:zettel/digest` + `:zettel/revision-id`
   automatically (per Decision 6 of miniforge-fleet's Phase E plan).

   Example:
     (create-zettel \"protocol-naming\" \"Protocol Naming Convention\"
                    \"# Protocol Naming...\" :rule
                    :dewey \"210\" :tags [:clojure :protocol])"
  [uid title content type
   & {:keys [dewey tags links source author
             fleet/shareable fleet/share-scope privacy/classification
             fleet/oss-version]
      :or   {author "user"}}]
  (let [now    (java.util.Date.)
        zettel (cond-> {:zettel/id      (random-uuid)
                        :zettel/uid     uid
                        :zettel/title   title
                        :zettel/content content
                        :zettel/type    type
                        :zettel/created now
                        :zettel/author  author}
                 dewey      (assoc :zettel/dewey dewey)
                 (seq tags) (assoc :zettel/tags (vec tags))
                 (seq links) (assoc :zettel/links (vec links))
                 source     (assoc :zettel/source source)
                 (some? shareable)      (assoc :fleet/shareable shareable)
                 share-scope            (assoc :fleet/share-scope share-scope)
                 classification         (assoc :privacy/classification classification)
                 oss-version            (assoc :fleet/oss-version oss-version))]
    (stamp-revision zettel)))

(defn update-zettel
  "Update a zettel with new values, setting modified timestamp.

   When `changes` touches any content-bearing field
   (`:zettel/uid` / `:zettel/title` / `:zettel/content` / `:zettel/type`
   / `:zettel/dewey` / `:zettel/tags` / `:zettel/links` /
   `:zettel/source`), the constructor recomputes `:zettel/digest` and
   rotates `:zettel/revision-id`. Operational-metadata-only changes
   (privacy classification, share scope, oss-version, etc.) leave the
   revision identity intact — Decision 6 attaches trust to the
   immutable revision, so changing an operational policy must NOT
   silently migrate trust onto a new revision."
  [zettel changes]
  (let [merged (-> zettel
                   (merge changes)
                   (assoc :zettel/modified (java.util.Date.)))]
    (if (content-changed? changes)
      (stamp-revision merged)
      merged)))

(defn zettel-summary
  "Extract a lightweight summary from a zettel."
  [zettel]
  (select-keys zettel [:zettel/id :zettel/uid :zettel/title
                       :zettel/type :zettel/dewey :zettel/tags]))

(defn validate-zettel
  "Validate a zettel against the schema."
  [zettel]
  (if (m/validate schema/Zettel zettel)
    {:valid? true :errors nil}
    {:valid? false :errors (m/explain schema/Zettel zettel)}))

;------------------------------------------------------------------------------ Layer 1
;; Link management

(defn create-link
  "Create a link to another zettel.

   Arguments:
   - target-id  - UUID of the target zettel
   - type       - Link type keyword
   - rationale  - Explanation of why this connection exists (required!)

   Options:
   - :strength        - Relevance weight 0.0-1.0
   - :bidirectional?  - Whether to create backlink

   Example:
     (create-link target-uuid :extends
                  'Adds namespace details to the base Clojure rule')"
  [target-id type rationale & {:keys [strength bidirectional?]}]
  (cond-> {:link/target-id target-id
           :link/type type
           :link/rationale rationale}
    strength (assoc :link/strength strength)
    (some? bidirectional?) (assoc :link/bidirectional? bidirectional?)))

(defn add-link
  "Add a link to a zettel."
  [zettel link]
  (let [links (get zettel :zettel/links [])]
    (-> zettel
        (assoc :zettel/links (conj links link))
        (assoc :zettel/modified (java.util.Date.)))))

(defn remove-link
  "Remove a link by target ID."
  [zettel target-id]
  (let [links (get zettel :zettel/links [])]
    (-> zettel
        (assoc :zettel/links (vec (remove #(= target-id (:link/target-id %)) links)))
        (assoc :zettel/modified (java.util.Date.)))))

(defn get-links
  "Get links from a zettel.

   Direction:
   - :outgoing  - Links from this zettel
   - :incoming  - Backlinks to this zettel
   - :both      - All connections"
  [zettel direction]
  (case direction
    :outgoing (get zettel :zettel/links [])
    :incoming (mapv (fn [id] {:link/target-id id :link/type :backlink})
                    (get zettel :zettel/backlinks []))
    :both (concat (get-links zettel :outgoing)
                  (get-links zettel :incoming))))

(defn compute-backlinks
  "Given a collection of zettels, compute backlinks for each.
   Returns map of zettel-id -> [source-ids...]"
  [zettels]
  (reduce
   (fn [acc zettel]
     (let [source-id (:zettel/id zettel)]
       (reduce
        (fn [acc2 link]
          (let [target-id (:link/target-id link)]
            (update acc2 target-id (fnil conj []) source-id)))
        acc
        (get zettel :zettel/links []))))
   {}
   zettels))

;------------------------------------------------------------------------------ Layer 2
;; Serialization (Markdown with YAML frontmatter)

(defn format-frontmatter
  "Convert zettel metadata to YAML frontmatter."
  [zettel]
  (let [meta (cond-> {:id (str (:zettel/id zettel))
                      :uid (:zettel/uid zettel)
                      :type (name (:zettel/type zettel))
                      :created (str (:zettel/created zettel))
                      :author (:zettel/author zettel)}
               (:zettel/dewey zettel) (assoc :dewey (:zettel/dewey zettel))
               (seq (:zettel/tags zettel)) (assoc :tags (mapv name (:zettel/tags zettel)))
               (:zettel/modified zettel) (assoc :modified (str (:zettel/modified zettel)))
               (seq (:zettel/links zettel))
               (assoc :links (mapv (fn [link]
                                     {:target (str (:link/target-id link))
                                      :type (name (:link/type link))
                                      :rationale (:link/rationale link)})
                                   (:zettel/links zettel))))]
    (yaml/generate-string meta :dumper-options {:flow-style :block})))

(defn zettel->markdown
  "Serialize a zettel to Markdown with YAML frontmatter."
  [zettel]
  (str "---\n"
       (format-frontmatter zettel)
       "---\n\n"
       "# " (:zettel/title zettel) "\n\n"
       (:zettel/content zettel)))

(defn parse-frontmatter
  "Parse YAML frontmatter from a string."
  [frontmatter-str]
  (try
    (yaml/parse-string frontmatter-str)
    (catch Exception _e
      nil)))

(defn extract-title-from-content
  "Extract title from first H1 heading in content."
  [content]
  (when-let [match (re-find #"^#\s+(.+)$" (first (str/split-lines content)))]
    (second match)))

(defn str->uuid
  "Parse a string as UUID, or return nil."
  [s]
  (try
    (java.util.UUID/fromString s)
    (catch Exception _e nil)))

(defn parse-inst
  "Parse a string as inst, or return nil."
  [s]
  (try
    (java.util.Date/from (java.time.Instant/parse s))
    (catch Exception _e
      ;; Try alternate format
      (try
        (let [fmt (java.text.SimpleDateFormat. "EEE MMM dd HH:mm:ss zzz yyyy")]
          (.parse fmt s))
        (catch Exception _e2 nil)))))

(defn markdown->zettel
  "Parse a Markdown file with YAML frontmatter into a zettel.
   Returns nil if parsing fails."
  [markdown-str]
  (when-let [matches (re-find #"(?s)^---\n(.+?)\n---\n\n?(.*)$" markdown-str)]
    (let [[_ frontmatter-str content] matches
          frontmatter (parse-frontmatter frontmatter-str)]
      (when frontmatter
        (let [title (or (extract-title-from-content content)
                        (:title frontmatter)
                        "Untitled")
              ;; Remove title line from content if present
              body (str/replace content #"^#\s+.+\n\n?" "")]
          (cond-> {:zettel/id (or (str->uuid (:id frontmatter)) (random-uuid))
                   :zettel/uid (:uid frontmatter)
                   :zettel/title title
                   :zettel/content body
                   :zettel/type (keyword (:type frontmatter))
                   :zettel/created (or (parse-inst (:created frontmatter))
                                       (java.util.Date.))
                   :zettel/author (get frontmatter :author "unknown")}
            (:dewey frontmatter) (assoc :zettel/dewey (:dewey frontmatter))
            (seq (:tags frontmatter)) (assoc :zettel/tags (mapv keyword (:tags frontmatter)))
            (:modified frontmatter) (assoc :zettel/modified (parse-inst (:modified frontmatter)))
            (seq (:links frontmatter))
            (assoc :zettel/links
                   (mapv (fn [link]
                           {:link/target-id (str->uuid (:target link))
                            :link/type (keyword (:type link))
                            :link/rationale (:rationale link)})
                         (:links frontmatter)))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a zettel
  (def z (create-zettel "210-clojure-ns" "Namespace Conventions"
                        "Follow Polylith structure for namespaces..."
                        :rule
                        :dewey "210"
                        :tags [:clojure :namespace]))
  z
  ;; => {:zettel/id #uuid "...", :zettel/uid "210-clojure-ns", ...}

  ;; Add a link
  (def z2 (add-link z (create-link (random-uuid) :extends
                                   "Extends base Clojure conventions")))

  ;; Serialize to markdown
  (println (zettel->markdown z2))

  ;; Round-trip
  (def z3 (markdown->zettel (zettel->markdown z2)))
  (= (:zettel/uid z2) (:zettel/uid z3))
  ;; => true

  ;; Validate
  (validate-zettel z)
  ;; => {:valid? true, :errors nil}

  :leave-this-here)
