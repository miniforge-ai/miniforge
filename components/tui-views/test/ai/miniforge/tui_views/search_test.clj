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

(ns ai.miniforge.tui-views.search-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-views.search :as search]))

(deftest fuzzy-score-test
  (testing "Exact prefix match scores high"
    (is (pos? (search/fuzzy-score "dep" "deploy-v2"))))

  (testing "Non-match returns 0"
    (is (zero? (search/fuzzy-score "xyz" "deploy-v2"))))

  (testing "Empty query returns 0"
    (is (zero? (search/fuzzy-score "" "deploy-v2"))))

  (testing "Empty text returns 0"
    (is (zero? (search/fuzzy-score "dep" ""))))

  (testing "Consecutive matches score higher than scattered"
    (let [consecutive (search/fuzzy-score "dep" "deploy-v2")
          scattered (search/fuzzy-score "dey" "deploy-v2")]
      ;; "dey" matches d, e (from deploy), y (from deploy) but scattered
      (is (> consecutive scattered))))

  (testing "Case insensitive"
    (is (= (search/fuzzy-score "DEP" "deploy-v2")
           (search/fuzzy-score "dep" "deploy-v2"))))

  (testing "Word boundary bonus"
    ;; "v2" starts at word boundary after '-'
    (is (pos? (search/fuzzy-score "v2" "deploy-v2")))))

(deftest search-workflows-test
  (testing "Finds matching workflows"
    (let [wfs [{:name "deploy-v2"}
               {:name "fix-bug-123"}
               {:name "deploy-prod"}]
          results (search/search-workflows wfs "dep")]
      (is (= 2 (count results)))
      (is (every? #(pos? (:score %)) results))))

  (testing "Results sorted by score"
    (let [wfs [{:name "deploy-v2"}
               {:name "deep-analysis"}
               {:name "deploy-prod"}]
          results (search/search-workflows wfs "dep")]
      ;; All should match, sorted by score descending
      (is (apply >= (map :score results)))))

  (testing "No match returns empty"
    (let [results (search/search-workflows [{:name "hello"}] "xyz")]
      (is (empty? results))))

  (testing "Empty query returns empty"
    (let [results (search/search-workflows [{:name "hello"}] "")]
      (is (empty? results)))))

(deftest search-all-test
  (testing "Searches across workflows and artifacts"
    (let [model {:workflows [{:name "deploy-v2"}]
                 :detail {:artifacts [{:name "deploy-plan.edn"}]}}
          results (search/search-all model "dep")]
      (is (>= (count results) 1))
      (is (every? #(pos? (:score %)) results)))))
