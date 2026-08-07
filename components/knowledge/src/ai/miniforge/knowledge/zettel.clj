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
  "Public zettel operations composed from focused representation domains."
  (:require
   [ai.miniforge.knowledge.schema :as schema]
   [ai.miniforge.knowledge.zettel.lifecycle :as lifecycle]
   [ai.miniforge.knowledge.zettel.revision :as revision]
   [clojure.string :as str]
   [clj-yaml.core :as yaml]
   [malli.core :as m])
  (:import
   [java.time Instant]
   [java.util Date Locale]))

;------------------------------------------------------------------------------ Layer 0

;; These aliases preserve the established zettel namespace API while each
;; implementation stays within its own three-stratum representation domain.
(def ^{:stratum 0} revision-id-from-digest revision/revision-id-from-digest)

(def ^{:stratum 0} compute-digest revision/compute-digest)

(defn ^{:stratum 0} ->iso
  "Convert an Instant or Date to its ISO-8601 wire representation."
  ^String [v]
  (cond
    (instance? Instant v) (.toString ^Instant v)
    (instance? Date v) (.toString (.toInstant ^Date v))
    :else (throw (ex-info "zettel timestamp is not a supported instant type"
                          {:value v :type (some-> v class .getName)}))))

(defn ^{:stratum 0} parse-frontmatter
  "Parse YAML frontmatter from a string."
  [frontmatter-str]
  (try
    (yaml/parse-string frontmatter-str)
    (catch Exception _ nil)))

(defn ^{:stratum 0} extract-title-from-content
  "Extract the first H1 heading from markdown content."
  [content]
  (when-let [match (re-find #"^#\s+(.+)$" (first (str/split-lines content)))]
    (second match)))

(defn ^{:stratum 0} str->uuid
  "Parse a string as UUID, returning nil for invalid input."
  [s]
  (try
    (java.util.UUID/fromString s)
    (catch Exception _ nil)))

(defn ^{:stratum 0} parse-inst
  "Parse the current ISO wire format or the legacy Date.toString format."
  [s]
  (try
    (Date/from (Instant/parse s))
    (catch Exception _
      (try
        (let [fmt (java.text.SimpleDateFormat. "EEE MMM dd HH:mm:ss zzz yyyy"
                                               Locale/ENGLISH)]
          (.parse fmt s))
        (catch Exception _ nil)))))

(defn ^{:stratum 0} zettel-summary
  "Return a lightweight zettel summary."
  [zettel]
  (select-keys zettel [:zettel/id :zettel/uid :zettel/title
                       :zettel/type :zettel/dewey :zettel/tags]))

(defn ^{:stratum 0} validate-zettel
  "Validate a zettel against its closed schema."
  [zettel]
  (if (m/validate schema/Zettel zettel)
    {:valid? true :errors nil}
    {:valid? false :errors (m/explain schema/Zettel zettel)}))

(defn ^{:stratum 0} create-link
  "Create a link to another zettel."
  [target-id type rationale & {:keys [strength bidirectional?]}]
  (cond-> {:link/target-id target-id
           :link/type type
           :link/rationale rationale}
    strength (assoc :link/strength strength)
    (some? bidirectional?) (assoc :link/bidirectional? bidirectional?)))

(defn ^{:stratum 0} add-link
  "Add one outgoing link and update the modification timestamp."
  [zettel link]
  (let [links (get zettel :zettel/links [])]
    (-> zettel
        (assoc :zettel/links (conj links link))
        (assoc :zettel/modified (java.util.Date.)))))

(defn ^{:stratum 0} remove-link
  "Remove outgoing links that target `target-id`."
  [zettel target-id]
  (let [links (get zettel :zettel/links [])]
    (-> zettel
        (assoc :zettel/links (vec (remove #(= target-id (:link/target-id %)) links)))
        (assoc :zettel/modified (java.util.Date.)))))

(defn ^{:stratum 0} get-links
  "Return outgoing, incoming, or all zettel links."
  [zettel direction]
  (case direction
    :outgoing (get zettel :zettel/links [])
    :incoming (mapv (fn [id] {:link/target-id id :link/type :backlink})
                    (get zettel :zettel/backlinks []))
    :both (concat (get-links zettel :outgoing)
                  (get-links zettel :incoming))))

(defn ^{:stratum 0} compute-backlinks
  "Build target-id to source-id backlinks for a collection of zettels."
  [zettels]
  (reduce
   (fn [acc zettel]
     (let [source-id (:zettel/id zettel)]
       (reduce (fn [links link]
                 (update links (:link/target-id link) (fnil conj []) source-id))
               acc
               (get zettel :zettel/links []))))
   {}
   zettels))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} format-frontmatter
  "Convert zettel metadata to YAML frontmatter."
  [zettel]
  (let [meta (cond-> {:id (str (:zettel/id zettel))
                      :uid (:zettel/uid zettel)
                      :type (name (:zettel/type zettel))
                      :created (->iso (:zettel/created zettel))
                      :author (:zettel/author zettel)}
               (:zettel/dewey zettel) (assoc :dewey (:zettel/dewey zettel))
               (seq (:zettel/tags zettel)) (assoc :tags (mapv name (:zettel/tags zettel)))
               (:zettel/modified zettel) (assoc :modified (->iso (:zettel/modified zettel)))
               (seq (:zettel/links zettel))
               (assoc :links (mapv (fn [link]
                                     {:target (str (:link/target-id link))
                                      :type (name (:link/type link))
                                      :rationale (:link/rationale link)})
                                   (:zettel/links zettel))))]
    (yaml/generate-string meta :dumper-options {:flow-style :block})))

(defn ^{:stratum 1} markdown->zettel
  "Parse a markdown zettel with YAML frontmatter."
  [markdown-str]
  (when-let [matches (re-find #"(?s)^---\n(.+?)\n---\n\n?(.*)$" markdown-str)]
    (let [[_ frontmatter-str content] matches
          frontmatter (parse-frontmatter frontmatter-str)]
      (when frontmatter
        (let [title (or (extract-title-from-content content)
                        (:title frontmatter)
                        "Untitled")
              body (str/replace content #"^#\s+.+\n\n?" "")]
          (cond-> {:zettel/id (or (str->uuid (:id frontmatter)) (random-uuid))
                   :zettel/uid (:uid frontmatter)
                   :zettel/title title
                   :zettel/content body
                   :zettel/type (keyword (:type frontmatter))
                   :zettel/created (or (parse-inst (:created frontmatter)) (Date.))
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

(defn ^{:stratum 1} stamp-revision
  "Attach the current content digest and deterministic revision id."
  [zettel]
  (let [digest (compute-digest zettel)]
    (assoc zettel
           :zettel/digest digest
           :zettel/revision-id (revision-id-from-digest digest))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} zettel->markdown
  "Serialize a zettel to markdown with YAML frontmatter."
  [zettel]
  (str "---\n"
       (format-frontmatter zettel)
       "---\n\n"
       "# " (:zettel/title zettel) "\n\n"
       (:zettel/content zettel)))

(defn ^{:stratum 2} create-zettel
  "Create a zettel and stamp its initial untrusted revision."
  [uid title content type
   & {:keys [dewey tags links source author
             fleet/shareable fleet/share-scope privacy/classification
             fleet/oss-version]
      :or {author "user"}}]
  (let [now (Date.)
        zettel (cond-> {:zettel/id (random-uuid) :zettel/uid uid
                         :zettel/title title :zettel/content content
                         :zettel/type type :zettel/created now
                         :zettel/author author :zettel/trust-level :untrusted}
                 dewey (assoc :zettel/dewey dewey)
                 (seq tags) (assoc :zettel/tags (vec tags))
                 (seq links) (assoc :zettel/links (vec links))
                 source (assoc :zettel/source source)
                 (some? shareable) (assoc :fleet/shareable shareable)
                 share-scope (assoc :fleet/share-scope share-scope)
                 classification (assoc :privacy/classification classification)
                 oss-version (assoc :fleet/oss-version oss-version))]
    (stamp-revision zettel)))

(defn ^{:stratum 2} update-zettel
  "Update a zettel while preserving or resetting trust by revision."
  [zettel changes]
  (let [old-rev (:zettel/revision-id zettel)
        old-trust (:zettel/trust-level zettel)
        sanitised (apply dissoc changes revision/derived-fields)
        merged (-> zettel (merge sanitised) (assoc :zettel/modified (Date.)))
        stamped (stamp-revision merged)
        new-rev (:zettel/revision-id stamped)
        next-trust (cond
                     (and (some? old-rev) (not= old-rev new-rev)) :untrusted
                     (some? old-trust) old-trust
                     :else :untrusted)]
    (assoc stamped :zettel/trust-level next-trust)))
