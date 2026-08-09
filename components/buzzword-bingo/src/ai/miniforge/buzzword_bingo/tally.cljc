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
(ns ai.miniforge.buzzword-bingo.tally
  "Break a list of hits down by category and by term.

   A single score says whether prose needs rewriting; these say which
   words to reach for first.")

;------------------------------------------------------------------------------ Layer 0

;; Weight
(defn ^{:stratum 0} weight-of
  "Total weight of `hits`."
  [hits]
  (reduce + 0 (map :hit/weight hits)))

;; Term breaks the count tie so the report is stable across runs.
(defn- ^{:stratum 0} descending-count [tally]
  [(- (:tally/count tally)) (str (:tally/term tally))])

;------------------------------------------------------------------------------ Layer 1

;; Grouped counts
(defn- ^{:stratum 1} category-tally [hits]
  {:tally/count    (count hits)
   :tally/weighted (weight-of hits)})

(defn- ^{:stratum 1} term-tally [[term-id hits]]
  (let [head (first hits)]
    {:tally/id       term-id
     :tally/term     (:hit/term head)
     :tally/category (:hit/category head)
     :tally/count    (count hits)
     :tally/weighted (weight-of hits)}))

;------------------------------------------------------------------------------ Layer 2

;; Published breakdowns
(defn ^{:stratum 2} by-category
  "Category key → count and weight, for every category present in `hits`."
  [hits]
  (update-vals (group-by :hit/category hits) category-tally))

(defn ^{:stratum 2} by-term
  "Per-term tallies for `hits`, heaviest first."
  [hits]
  (->> (group-by :hit/id hits)
       (map term-tally)
       (sort-by descending-count)
       vec))

(comment
  (by-category [{:hit/id :robust :hit/term "robust"
                 :hit/category :marketing :hit/weight 3}]))
