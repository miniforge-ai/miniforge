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

(ns ai.miniforge.tui-views.search-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-views.test-util :as util]
   [ai.miniforge.tui-views.update :as update]
   [ai.miniforge.tui-views.update.mode :as mode]))

(def wf-id-1 (random-uuid))
(def wf-id-2 (random-uuid))
(def wf-id-3 (random-uuid))

(defn- three-workflows []
  (util/with-workflows (util/fresh-model)
    [{:workflow-id wf-id-1 :name "deploy-api"}
     {:workflow-id wf-id-2 :name "deploy-web"}
     {:workflow-id wf-id-3 :name "build-infra"}]))

(deftest search-filtering-test
  (testing "Search filters workflows by name"
    (let [m (-> (three-workflows)
                (assoc :mode :search :command-buf "/deploy")
                mode/compute-search-results)]
      (is (= #{0 1} (:filtered-indices m)))))

  (testing "Search is case-insensitive"
    (let [m (-> (three-workflows)
                (assoc :mode :search :command-buf "/DEPLOY")
                mode/compute-search-results)]
      (is (= #{0 1} (:filtered-indices m)))))

  (testing "Empty query clears filter"
    (let [m (-> (three-workflows)
                (assoc :mode :search :command-buf "/")
                mode/compute-search-results)]
      (is (nil? (:filtered-indices m)))))

  (testing "No matches gives empty set"
    (let [m (-> (three-workflows)
                (assoc :mode :search :command-buf "/zzzzz")
                mode/compute-search-results)]
      (is (empty? (:filtered-indices m)))))

  (testing "Single match"
    (let [m (-> (three-workflows)
                (assoc :mode :search :command-buf "/infra")
                mode/compute-search-results)]
      (is (= #{2} (:filtered-indices m))))))

(deftest search-mode-escape-test
  (testing "Escape from search mode clears filtered-indices"
    (let [m (-> (three-workflows)
                (update/update-model [:input :key/slash])
                (update/update-model [:input {:type :char :char \d}])
                (update/update-model [:input :key/escape]))]
      (is (util/mode-is? m :normal))
      (is (nil? (:filtered-indices m))))))

(deftest search-resets-selection-test
  (testing "Search resets selected-idx to 0"
    (let [m (-> (three-workflows)
                (assoc :selected-idx 2 :mode :search :command-buf "/deploy")
                mode/compute-search-results)]
      (is (= 0 (:selected-idx m))))))
