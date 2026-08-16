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
(ns ai.miniforge.buzzword-bingo.score-test
  (:require
   [ai.miniforge.buzzword-bingo.score :as sut]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures and helpers
;; Mirrors the shape detect/scan emits: hits carry an id, display term,
;; category and weight, and the scan carries the prose word count.
(defn- ^{:stratum 0} hit
  [term category weight]
  {:hit/id       (keyword term)
   :hit/term     term
   :hit/category category
   :hit/weight   weight})

(defn- ^{:stratum 0} scan-of
  [word-count & hits]
  {:scan/hits (vec hits) :scan/word-count word-count})

(defn- ^{:stratum 0} graded
  ([scan] (graded scan nil))
  ([scan opts] (:score/grade (sut/summarize scan opts))))

;------------------------------------------------------------------------------ Layer 1

;; Rates and grades
(deftest ^{:stratum 1} test-grade-follows-the-weighted-rate
  (testing "given prose with no hits → clean"
    (is (= :clean (graded (scan-of 500)))))

  (testing "given a dense run of heavy terms → slop"
    (is (= :slop (graded (apply scan-of 100 (repeat 10 (hit "robust" :marketing 3)))))))

  (testing "given caller thresholds → they replace the defaults"
    (is (= :slop (graded (scan-of 400 (hit "robust" :marketing 3))
                         {:thresholds {:threshold/suspect 1.0 :threshold/slop 2.0}}))))

  (testing "given only one boundary → the other keeps its default rather than
            being compared against nothing"
    (is (= :suspect (graded (scan-of 400 (hit "robust" :marketing 3))
                            {:thresholds {:threshold/suspect 1.0}})))))

(deftest ^{:stratum 1} test-short-texts-are-damped-but-not-exempt
  (testing "given a lone filler word in a short reply → clean, not a false alarm"
    (is (= :clean (graded (scan-of 10 (hit "very" :filler 1))))))

  (testing "given a marketing adjective in the same short reply → flagged"
    (is (= :slop (graded (scan-of 10 (hit "robust" :marketing 3))))))

  (testing "given one hit → smoothing keeps a short text below a saturated long one"
    (let [short-reply (sut/summarize (scan-of 10 (hit "robust" :marketing 3)))
          long-doc    (sut/summarize (apply scan-of 1000 (repeat 30 (hit "robust" :marketing 3))))]
      (is (< (:score/value short-reply) (:score/value long-doc))))))

;; Tallies
(deftest ^{:stratum 1} test-tallies-break-the-score-down
  (testing "given hits across categories → each category reports count and weight"
    (let [scored (sut/summarize (scan-of 200
                                         (hit "robust" :marketing 3)
                                         (hit "delve" :ai-tell 5)
                                         (hit "synergy" :corporate 2)))]
      (is (= {:marketing {:tally/count 1 :tally/weighted 3}
              :ai-tell   {:tally/count 1 :tally/weighted 5}
              :corporate {:tally/count 1 :tally/weighted 2}}
             (:score/by-category scored)))
      (is (= 10 (:score/weighted scored)))))

  (testing "given a repeated term → the per-term tally leads, heaviest first"
    (let [scored (sut/summarize (apply scan-of 200
                                       (hit "delve" :ai-tell 5)
                                       (repeat 3 (hit "robust" :marketing 3))))]
      (is (= ["robust" "delve"] (mapv :tally/term (:score/by-term scored))))
      (is (= 3 (:tally/count (first (:score/by-term scored))))))))
