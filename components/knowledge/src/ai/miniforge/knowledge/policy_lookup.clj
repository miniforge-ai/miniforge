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

(ns ai.miniforge.knowledge.policy-lookup
  "Pure query functions for searching loaded policy-pack rules.

   Searches only :rule-type zettels (those loaded from .mdc rule files
   or policy packs). All criteria are optional; omitting all returns all rules.
   Multiple criteria are ANDed.

   Layer 0: query translation + result projection
   Layer 1: lookup orchestration"
  (:require
   [ai.miniforge.knowledge.store :as store]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Query translation and result projection

(defn- dewey-prefixes
  "Coerce a scalar dewey-prefix string into the list form the store expects.
   Returns nil when the prefix is nil, blank, or whitespace-only."
  [dewey-prefix]
  (let [trimmed (str/trim (or dewey-prefix ""))]
    (when (seq trimmed)
      [trimmed])))

(defn- normalise-query-string
  "Return nil for nil/blank query, otherwise the trimmed string.
   Ensures the store text-search filter is skipped when no keyword was given."
  [q]
  (let [trimmed (str/trim (or q ""))]
    (when (seq trimmed) trimmed)))

(defn- build-store-query
  "Translate a PolicyQuery map to the KnowledgeStore query map.
   Always restricts to :rule type so only policy-pack rules are searched."
  [{:keys [query tags dewey-prefix]}]
  (let [text-search (normalise-query-string query)
        prefixes    (dewey-prefixes dewey-prefix)]
    (cond-> {:include-types [:rule]}
      text-search (assoc :text-search text-search)
      (seq tags)  (assoc :tags (vec tags))
      prefixes    (assoc :dewey-prefixes prefixes))))

(defn- zettel->policy-result
  "Project a zettel into the compact {:rule/title :rule/content} shape."
  [zettel]
  {:rule/title   (:zettel/title zettel)
   :rule/content (:zettel/content zettel)})

;------------------------------------------------------------------------------ Layer 1
;; Lookup orchestration

(defn lookup-policy-rules
  [knowledge-store policy-query]
  (if (nil? knowledge-store)
    []
    (->> (store/query knowledge-store (build-store-query policy-query))
         (mapv zettel->policy-result))))

(comment
  ;; Example: search for all Clojure-tagged rules
  ;; (store ns already required above; alias available as `store`)
  (def s (store/create-store))
  (lookup-policy-rules s {:tags [:clojure]})

  ;; Search by keyword
  (lookup-policy-rules s {:query "try+"})

  ;; Search by Dewey prefix
  (lookup-policy-rules s {:dewey-prefix "210"})

  ;; Combined
  (lookup-policy-rules s {:query "namespace" :tags [:clojure] :dewey-prefix "210"})

  ;; nil store → empty vector (safe to call unconditionally)
  (lookup-policy-rules nil {})
  ;; => []
  :end)
