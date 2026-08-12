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
(ns ai.miniforge.buzzword-bingo.interface-test
  (:require
   [ai.miniforge.buzzword-bingo.interface :as sut]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures and helpers
(def ^{:stratum 0} ^:private plain-report
  "Prose of the kind the scanner should leave alone: what changed, and
   what it means, with no claim about how good any of it is."
  (str "The loop condition used the wrong comparison, so the last index "
       "was skipped. The fix changes it and the two failing tests pass."))

(def ^{:stratum 0} ^:private generated-pitch
  "The register the scanner exists to catch."
  (str "Our comprehensive platform delivers a seamless, robust experience "
       "that empowers teams in todays fast-paced landscape. This "
       "game-changing solution is a testament to best-in-class engineering."))

(defn- ^{:stratum 0} graded [text]
  (:score/grade (sut/scan text)))

(deftest ^{:stratum 0} test-catalog-is-reachable-through-the-interface
  (testing "given the shipped build → entries, categories and a version are exposed"
    (is (seq (sut/entries)))
    (is (contains? (sut/categories) :marketing))
    (is (string? (sut/lexicon-version)))
    (is (contains? (sut/default-thresholds) :threshold/slop))))

;------------------------------------------------------------------------------ Layer 1

;; End to end through the shipped catalog
(deftest ^{:stratum 1} test-scan-separates-plain-prose-from-generated-pitch
  (testing "given a plain change report → clean, with nothing flagged"
    (is (= :clean (graded plain-report)))
    (is (empty? (:scan/hits (sut/scan plain-report)))))

  (testing "given generated marketing prose → slop"
    (is (= :slop (graded generated-pitch))))

  (testing "given a hit → it carries the position and context needed to fix it"
    (let [hit (first (:scan/hits (sut/scan generated-pitch)))]
      (is (every? hit [:hit/term :hit/category :hit/weight :hit/line :hit/column :hit/context])))))

(deftest ^{:stratum 1} test-scan-ignores-what-is-not-prose
  (testing "given a buzzword only inside code → nothing is charged"
    (is (= :clean (graded (str plain-report "\n\n```\n(def robust-comprehensive 1)\n```")))))

  (testing "given caller thresholds → the grade moves with them"
    (is (= :suspect (:score/grade
                     (sut/scan generated-pitch
                               {:thresholds {:threshold/suspect 1.0
                                             :threshold/slop    1e6}}))))))
