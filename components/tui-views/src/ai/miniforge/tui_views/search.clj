;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.tui-views.search
  "Fuzzy search for TUI navigation.

   Provides fuzzy matching across workflow names, artifact types, and
   other searchable content. Uses a simple scoring algorithm:
   - Consecutive character matches score higher
   - Matches at word boundaries score higher
   - Case-insensitive matching"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Fuzzy matching

(defn- char-match-score
  "Score a single character match.
   consecutive? - true if previous char also matched
   boundary?    - true if at word boundary (after space, -, _, /)"
  [consecutive? boundary?]
  (cond-> 1
    consecutive? (+ 2)
    boundary?    (+ 1)))

(defn fuzzy-score
  "Compute fuzzy match score for query against text.
   Returns 0 if no match, higher scores indicate better matches.

   Algorithm:
   - Walk through query chars, finding each in text (left to right)
   - Score bonuses for consecutive matches and word boundaries
   - Returns 0 if any query char not found"
  [query text]
  (let [q (str/lower-case (or query ""))
        t (str/lower-case (or text ""))
        qlen (count q)
        tlen (count t)]
    (if (or (zero? qlen) (zero? tlen))
      0
      (loop [qi 0          ; query index
             ti 0          ; text index
             score 0
             last-match -2 ; position of last match in text
             ]
        (if (>= qi qlen)
          ;; All query chars matched -- add length bonus (prefer shorter texts)
          (+ score (max 0 (- 10 (quot tlen 5))))
          (if (>= ti tlen)
            ;; Ran out of text before matching all query chars
            0
            (let [qc (nth q qi)
                  tc (nth t ti)]
              (if (= qc tc)
                (let [consecutive? (= (dec ti) last-match)
                      boundary? (or (zero? ti)
                                    (let [prev (nth t (dec ti))]
                                      (or (= prev \space)
                                          (= prev \-)
                                          (= prev \_)
                                          (= prev \/))))]
                  (recur (inc qi) (inc ti)
                         (+ score (char-match-score consecutive? boundary?))
                         ti))
                (recur qi (inc ti) score last-match)))))))))

;------------------------------------------------------------------------------ Layer 1
;; Search across model

(defn search-workflows
  "Search workflows by name. Returns sorted results.

   Each result: {:workflow wf :score int}"
  [workflows query]
  (if (str/blank? query)
    []
    (->> workflows
         (map (fn [wf]
                {:workflow wf
                 :score (fuzzy-score query (:name wf))}))
         (filter #(pos? (:score %)))
         (sort-by :score >))))

(defn search-all
  "Search across all searchable content in the model.
   Returns vector of {:type :label :score :data} maps."
  [model query]
  (if (str/blank? query)
    []
    (let [wf-results (map (fn [{:keys [workflow score]}]
                            {:type :workflow
                             :label (:name workflow)
                             :score score
                             :data workflow})
                          (search-workflows (:workflows model) query))
          artifact-results (map (fn [a]
                                  {:type :artifact
                                   :label (or (:name a) (:path a) "unnamed")
                                   :score (fuzzy-score query (or (:name a) ""))
                                   :data a})
                                (get-in model [:detail :artifacts] []))]
      (->> (concat wf-results artifact-results)
           (filter #(pos? (:score %)))
           (sort-by :score >)
           (vec)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (fuzzy-score "dep" "deploy-v2")
  ;; => high score (consecutive match at start)

  (fuzzy-score "v2" "deploy-v2")
  ;; => moderate score

  (fuzzy-score "xyz" "deploy-v2")
  ;; => 0

  (search-workflows [{:name "deploy-v2"} {:name "fix-bug"} {:name "deploy-prod"}]
                     "dep")
  ;; => [{:workflow {:name "deploy-v2"} :score ...} {:workflow {:name "deploy-prod"} :score ...}]

  :leave-this-here)
