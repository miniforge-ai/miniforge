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
(ns ai.miniforge.codex-gap.attribute
  "Attribute a failure signal to a codex problem (T2 §3.4).

   Two methods, tried in order:
   1. :mechanical — typed gate-failure reasons looked up in the declared
      [:reason/code :reason/rule-id] -> problem-id map (data resource).
   2. :similarity — character-bigram Dice similarity of the signal text
      against each problem's title + open items (the same corpus family
      the zk admit §6.3 matcher uses). Deliberately textual, deliberately
      dependency-free: high-confidence match proves PRESENCE; low
      confidence proves nothing and must land in the review queue, never
      silently in a bucket."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-threshold
  "Similarity floor below which attribution is not claimed."
  0.5)

(defn- ^{:stratum 0} bigrams [s]
  (let [normalized (-> (str/lower-case (str s))
                       (str/replace #"[^a-z0-9]+" " ")
                       str/trim)]
    (set (map #(apply str %) (partition 2 1 normalized)))))

(defn ^{:stratum 0} load-gate-reason-map
  "The declared mechanical map from the classpath resource; {} when absent
   (attribution then falls through to similarity for everything)."
  []
  (if-let [r (io/resource "config/codex-gap/gate-reason-map.edn")]
    (edn/read-string (slurp r))
    {}))

(defn- ^{:stratum 0} problem-corpus [problem]
  (str/join " " (cons (str (:title problem))
                      (map :value (:open problem)))))

(defn ^{:stratum 0} mechanical
  "Mechanical attribution for a gate-failure Reason map, or nil.
   Tries the exact [:reason/code :reason/rule-id] pair, then the
   rule-id-agnostic [:reason/code nil] entry."
  [reason gate-reason-map]
  (when-let [code (:reason/code reason)]
    (when-let [problem-id (or (get gate-reason-map [code (:reason/rule-id reason)])
                              (get gate-reason-map [code nil]))]
      {:problem problem-id :method :mechanical :confidence 1.0})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} dice
  "Sørensen–Dice coefficient over character bigrams; 0.0 when either side
   is empty (an empty corpus proves nothing)."
  [a b]
  (let [ba (bigrams a) bb (bigrams b)]
    (if (or (empty? ba) (empty? bb))
      0.0
      (/ (* 2.0 (count (filter bb ba)))
         (+ (count ba) (count bb))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} similarity
  "Best similarity attribution of `text` against `problems`
   (seq of {:id :title :open}). Always returns the best candidate with its
   confidence — the CALLER compares against the threshold and routes
   below-threshold results to the review queue (T2 §3.4)."
  [text problems]
  (when (seq problems)
    (let [scored (map (fn [p] {:problem (:id p)
                               :method :similarity
                               :confidence (dice text (problem-corpus p))})
                      problems)]
      (apply max-key :confidence scored))))
