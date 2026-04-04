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

(ns ai.miniforge.pr-train.risk-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.pr-train.risk :as risk]))

;; ============================================================================
;; Risk level classification tests
;; ============================================================================

(deftest score->level-test
  (testing "critical threshold"
    (is (= :critical (risk/score->level 0.75)))
    (is (= :critical (risk/score->level 1.0))))

  (testing "high threshold"
    (is (= :high (risk/score->level 0.50)))
    (is (= :high (risk/score->level 0.74))))

  (testing "medium threshold"
    (is (= :medium (risk/score->level 0.25)))
    (is (= :medium (risk/score->level 0.49))))

  (testing "low threshold"
    (is (= :low (risk/score->level 0.0)))
    (is (= :low (risk/score->level 0.24)))))

;; ============================================================================
;; Risk assessment tests
;; ============================================================================

(deftest assess-risk-test
  (testing "low-risk PR"
    (let [result (risk/assess-risk
                  {:train/prs []}
                  {:pr/number 1 :pr/blocks []}
                  {:change-size {:additions 10 :deletions 5}
                   :test-coverage-delta 2.0
                   :changed-files ["src/utils.clj"]
                   :hours-since-last-review 1
                   :complexity-delta 0}
                  {:total-commits 100 :recent-commits 20})]
      (is (= :low (:risk/level result)))
      (is (< (:risk/score result) 0.25))))

  (testing "high-risk PR with large changes and critical files"
    (let [result (risk/assess-risk
                  {:train/prs []}
                  {:pr/number 1 :pr/blocks [2 3 4]}
                  {:change-size {:additions 800 :deletions 300}
                   :test-coverage-delta -8.0
                   :changed-files ["Dockerfile" "schema.sql" ".env"]
                   :hours-since-last-review 96
                   :complexity-delta 15}
                  {:total-commits 2 :recent-commits 1})]
      (is (#{:high :critical} (:risk/level result)))
      (is (>= (:risk/score result) 0.50))))

  (testing "result contains factors"
    (let [result (risk/assess-risk
                  {:train/prs []}
                  {:pr/number 1 :pr/blocks []}
                  {} {})]
      (is (vector? (:risk/factors result)))
      (is (= 7 (count (:risk/factors result))))))

  (testing "factors have required keys"
    (let [result (risk/assess-risk
                  {:train/prs []}
                  {:pr/number 1 :pr/blocks []}
                  {:change-size {:additions 50 :deletions 10}}
                  {:total-commits 10})]
      (doseq [f (:risk/factors result)]
        (is (contains? f :factor))
        (is (contains? f :weight))
        (is (contains? f :score))
        (is (contains? f :explanation)))))

  (testing "risk score is weighted sum of factors"
    (let [result (risk/assess-risk
                  {:train/prs []}
                  {:pr/number 1 :pr/blocks []}
                  {:change-size {:additions 200 :deletions 50}}
                  {:total-commits 10})
          expected-score (reduce + 0.0
                                 (map (fn [f] (* (:weight f) (:score f)))
                                      (:risk/factors result)))]
      (is (< (Math/abs (- (:risk/score result) expected-score)) 0.001)))))

;; ============================================================================
;; Factor detail tests
;; ============================================================================

(deftest change-size-factor-test
  (testing "small change is low risk"
    (let [result (risk/assess-risk {:train/prs []} {:pr/number 1 :pr/blocks []}
                                   {:change-size {:additions 10 :deletions 5}} {})
          factor (first (filter #(= :change-size (:factor %)) (:risk/factors result)))]
      (is (= 0.0 (:score factor)))))

  (testing "huge change is high risk"
    (let [result (risk/assess-risk {:train/prs []} {:pr/number 1 :pr/blocks []}
                                   {:change-size {:additions 800 :deletions 300}} {})
          factor (first (filter #(= :change-size (:factor %)) (:risk/factors result)))]
      (is (= 1.0 (:score factor))))))

(deftest critical-files-factor-test
  (testing "no critical files is zero risk"
    (let [result (risk/assess-risk {:train/prs []} {:pr/number 1 :pr/blocks []}
                                   {:changed-files ["src/core.clj"]} {})
          factor (first (filter #(= :critical-files (:factor %)) (:risk/factors result)))]
      (is (= 0.0 (:score factor)))))

  (testing "Dockerfile modification is risky"
    (let [result (risk/assess-risk {:train/prs []} {:pr/number 1 :pr/blocks []}
                                   {:changed-files ["Dockerfile"]} {})
          factor (first (filter #(= :critical-files (:factor %)) (:risk/factors result)))]
      (is (= 0.50 (:score factor))))))
