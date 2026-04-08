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

(ns ai.miniforge.policy-pack.mapping
  "Mapping artifacts — first-class bridges between policy systems.

   A mapping artifact connects rules/categories in one system (source) to
   controls/requirements in another (target). Neither source nor target owns
   the mapping; it is an independent, versioned artifact.

   Layer 0: Malli schemas for MappingArtifact, MappingEntry, MappingAuthorship
   Layer 1: Loading and validation
   Layer 2: Resolution and report projection

   Related:
     specs/normative/N4-policy-packs.md §2.4 — Mapping artifact spec
     docs/design/policy-pack-taxonomy.md §2.4 — Four-artifact model design"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas

(def MappingType
  "How closely the source satisfies the target."
  [:enum :exact :broad :partial :none])

(def MappingConfidence
  "Confidence in the mapping's accuracy."
  [:enum :high :medium :low :unvalidated])

(def MappingAuthorship
  "Provenance metadata for a mapping artifact."
  [:map
   [:publisher keyword?]
   [:confidence MappingConfidence]
   [:validated-at {:optional true} string?]])

(def MappingSystemRef
  "Reference to a source or target system."
  [:map
   [:mapping/system-kind [:enum :pack :taxonomy :framework]]
   [:mapping/system-id keyword?]
   [:mapping/system-version {:optional true} string?]])

(def MappingEntry
  "A single mapping between a source rule/category and a target control."
  [:map
   [:source/rule {:optional true} keyword?]
   [:source/category {:optional true} keyword?]
   [:target/control {:optional true} [:maybe string?]]
   [:mapping/type MappingType]
   [:mapping/notes {:optional true} string?]])

(def MappingArtifact
  "Schema for a mapping artifact — bridges between policy systems."
  [:map
   [:mapping/id keyword?]
   [:mapping/version string?]
   [:mapping/source MappingSystemRef]
   [:mapping/target MappingSystemRef]
   [:mapping/entries [:vector MappingEntry]]
   [:mapping/authorship {:optional true} MappingAuthorship]])

;------------------------------------------------------------------------------ Layer 1
;; Validation

(defn valid-mapping?
  "Check if value conforms to the MappingArtifact schema."
  [value]
  (m/validate MappingArtifact value))

(defn validate-mapping
  "Validate a mapping artifact. Returns {:valid? bool :errors map-or-nil}."
  [value]
  (if (m/validate MappingArtifact value)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain MappingArtifact value))}))

;------------------------------------------------------------------------------ Layer 1
;; Loading

(defn load-mapping
  "Load a mapping artifact from an EDN file.

   Arguments:
   - path — Path to the .edn mapping file

   Returns:
   - {:success? true :mapping <MappingArtifact>}
   - {:success? false :error <message>}"
  [path]
  (try
    (let [file    (io/file path)
          content (slurp file)
          mapping (edn/read-string content)]
      (if (valid-mapping? mapping)
        {:success? true :mapping mapping}
        {:success? false
         :error (str "Mapping validation failed: "
                     (:errors (validate-mapping mapping)))}))
    (catch Exception e
      {:success? false :error (.getMessage e)})))

;------------------------------------------------------------------------------ Layer 2
;; Resolution

(defn resolve-mapping
  "Resolve a mapping against a pack, producing matched entries.

   For each mapping entry, checks whether the source rule or category
   exists in the pack and enriches the entry with the match status.

   Arguments:
   - mapping — MappingArtifact
   - pack    — PackManifest to match against

   Returns:
   - Vector of maps: {:source/rule :source/category :target/control
                       :mapping/type :matched? :rule-title}"
  [mapping pack]
  (let [pack-rule-ids (set (map :rule/id (:pack/rules pack)))
        pack-cat-ids  (set (map :category/id (:pack/categories pack)))]
    (mapv (fn [entry]
            (let [rule-match (when-let [rid (:source/rule entry)]
                               (contains? pack-rule-ids rid))
                  cat-match  (when-let [cid (:source/category entry)]
                               (contains? pack-cat-ids cid))
                  matched?   (boolean (or rule-match cat-match))
                  rule-title (when (and rule-match (:source/rule entry))
                               (:rule/title
                                (first (filter #(= (:rule/id %) (:source/rule entry))
                                               (:pack/rules pack)))))]
              (cond-> (assoc entry :matched? matched?)
                rule-title (assoc :rule-title rule-title))))
          (:mapping/entries mapping))))

(defn project-report
  "Generate a compliance coverage report by projecting a mapping onto a pack.

   Shows which target controls are covered (:exact, :broad, :partial),
   which have no mapping (:none), and which are unmatched (source rule/category
   not found in pack).

   Arguments:
   - mapping — MappingArtifact
   - pack    — PackManifest

   Returns:
   - {:target-controls [{:control :type :matched? :source :notes} ...]
      :summary {:exact int :broad int :partial int :none int :unmatched int}}"
  [mapping pack]
  (let [resolved (resolve-mapping mapping pack)
        controls (mapv (fn [entry]
                         {:control  (:target/control entry)
                          :type     (:mapping/type entry)
                          :matched? (:matched? entry)
                          :source   (or (:source/rule entry) (:source/category entry))
                          :notes    (:mapping/notes entry)})
                       resolved)
        summary  (reduce (fn [acc entry]
                           (if (:matched? entry)
                             (update acc (:type entry) (fnil inc 0))
                             (update acc :unmatched (fnil inc 0))))
                         {:exact 0 :broad 0 :partial 0 :none 0 :unmatched 0}
                         controls)]
    {:target-controls controls
     :summary         summary}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example mapping: miniforge rules → Vanta controls
  (def example-mapping
    {:mapping/id      :miniforge-to-vanta/core-2026
     :mapping/version "1.0.0"
     :mapping/source  {:mapping/system-kind :pack
                       :mapping/system-id   :miniforge/core
                       :mapping/system-version "1.0.0"}
     :mapping/target  {:mapping/system-kind :framework
                       :mapping/system-id   :vanta/soc2}
     :mapping/entries [{:source/rule    :mf.rule/copyright-header
                        :target/control "CC6.2"
                        :mapping/type   :exact}
                       {:source/category :mf.cat/operations
                        :target/control  "CC7.1"
                        :mapping/type    :broad}]
     :mapping/authorship {:publisher   :miniforge
                          :confidence  :high
                          :validated-at "2026-04-01"}})

  (valid-mapping? example-mapping)
  ;; => true

  :leave-this-here)
