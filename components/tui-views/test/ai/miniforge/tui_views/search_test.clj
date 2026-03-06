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
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-views.test-util :as util]
   [ai.miniforge.tui-views.update :as update]
   [ai.miniforge.tui-views.update.mode :as mode]
   [ai.miniforge.tui-views.update.navigation :as nav]))

(def wf-id-1 (random-uuid))
(def wf-id-2 (random-uuid))
(def wf-id-3 (random-uuid))

(defn three-workflows []
  (-> (util/fresh-model)
      (util/with-workflows
        [{:workflow-id wf-id-1 :name "deploy-api"}
         {:workflow-id wf-id-2 :name "deploy-web"}
         {:workflow-id wf-id-3 :name "build-infra"}])
      (assoc :view :workflow-list)))

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
      (is (= #{2} (:filtered-indices m)))))

  (testing "Repo-manager search filters configured repositories"
    (let [m (-> (util/fresh-model)
                (assoc :view :repo-manager
                       :fleet-repos ["acme/api" "acme/web" "infra/ops"]
                       :mode :search
                       :command-buf "/acme")
                mode/compute-search-results)]
      (is (= #{0 1} (:filtered-indices m)))))

  (testing "Repo-manager search in browse mode filters browse candidates"
    (let [m (-> (util/fresh-model)
                (assoc :view :repo-manager
                       :repo-manager-source :browse
                       :fleet-repos ["acme/api"]
                       :browse-repos ["acme/api" "acme/new-service" "org/other"]
                       :mode :search
                       :command-buf "/new")
                mode/compute-search-results)]
      ;; browse candidates excludes acme/api because it's already in fleet
      (is (= #{0} (:filtered-indices m))))))

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

;; ---------------------------------------------------------------------------
;; Selection survives search exit
;; ---------------------------------------------------------------------------

(deftest search-confirm-keeps-filter-test
  (testing "confirm-search keeps filtered-indices but exits to normal"
    (let [m (-> (three-workflows)
                (assoc :mode :search :command-buf "/deploy")
                mode/compute-search-results
                mode/confirm-search)]
      (is (= :normal (:mode m)))
      (is (= "" (:command-buf m)))
      ;; Filter persists!
      (is (= #{0 1} (:filtered-indices m)))))

  (testing "exit-mode clears filtered-indices"
    (let [m (-> (three-workflows)
                (assoc :mode :search :command-buf "/deploy")
                mode/compute-search-results
                mode/exit-mode)]
      (is (= :normal (:mode m)))
      (is (nil? (:filtered-indices m)))))

  (testing "Selection persists through filter clear"
    (let [m (-> (three-workflows)
                (assoc :filtered-indices #{0})
                (assoc :selected-ids #{wf-id-1})
                (assoc :mode :search :command-buf "/deploy-api"))]
      ;; Even exit-mode (Escape) preserves selection
      (is (= 1 (count (:selected-ids (mode/exit-mode m)))))
      (is (contains? (:selected-ids (mode/exit-mode m)) wf-id-1)))))

;; ---------------------------------------------------------------------------
;; Detail view search — find-in-page
;; ---------------------------------------------------------------------------

(defn detail-model-with-output
  "Create a model in workflow-detail with agent output text."
  [output-text]
  (let [wf-id (random-uuid)]
    (-> (util/fresh-model)
        (assoc :view :workflow-detail)
        (assoc-in [:detail :workflow-id] wf-id)
        (assoc-in [:detail :agent-output] output-text)
        (assoc :workflows [{:id wf-id :name "test-wf" :status :running}]))))

(deftest workflow-detail-search-test
  (testing "Search in workflow-detail finds matching lines"
    (let [m (-> (detail-model-with-output "line one\nerror here\nline three\nanother error")
                (assoc :mode :search :command-buf "/error")
                mode/compute-search-results)]
      (is (= 2 (count (:search-matches m))))
      (is (= 1 (:line-idx (first (:search-matches m)))))
      (is (= 3 (:line-idx (second (:search-matches m)))))
      (is (nil? (:filtered-indices m)) "Detail search doesn't set filtered-indices")))

  (testing "Empty query clears search-matches"
    (let [m (-> (detail-model-with-output "some text")
                (assoc :mode :search :command-buf "/")
                mode/compute-search-results)]
      (is (empty? (:search-matches m)))))

  (testing "No matches gives empty vector"
    (let [m (-> (detail-model-with-output "no matches here")
                (assoc :mode :search :command-buf "/zzzzz")
                mode/compute-search-results)]
      (is (empty? (:search-matches m)))))

  (testing "Search is case-insensitive"
    (let [m (-> (detail-model-with-output "Error on line 1\nerror on line 2")
                (assoc :mode :search :command-buf "/ERROR")
                mode/compute-search-results)]
      (is (= 2 (count (:search-matches m)))))))

(deftest workflow-detail-confirm-search-test
  (testing "Confirm search jumps to first match"
    (let [m (-> (detail-model-with-output "line one\ntarget here\nline three")
                (assoc :mode :search :command-buf "/target")
                mode/compute-search-results
                mode/confirm-search)]
      (is (= :normal (:mode m)))
      (is (= 0 (:search-match-idx m)))
      (is (= 1 (:scroll-offset m)) "Scrolls to match line")))

  (testing "Exit-mode clears search-matches"
    (let [m (-> (detail-model-with-output "target here")
                (assoc :mode :search :command-buf "/target")
                mode/compute-search-results
                mode/exit-mode)]
      (is (empty? (:search-matches m)))
      (is (nil? (:search-match-idx m))))))

(deftest search-match-navigation-test
  (testing "n jumps to next match"
    (let [m (-> (detail-model-with-output "match\nno\nmatch\nno\nmatch")
                (assoc :mode :search :command-buf "/match")
                mode/compute-search-results
                mode/confirm-search)]
      ;; Start at match 0 (line 0)
      (is (= 0 (:search-match-idx m)))
      ;; n → match 1 (line 2)
      (let [m2 (nav/next-search-match m)]
        (is (= 1 (:search-match-idx m2)))
        (is (= 2 (:scroll-offset m2))))
      ;; n twice → match 2 (line 4)
      (let [m3 (-> m nav/next-search-match nav/next-search-match)]
        (is (= 2 (:search-match-idx m3)))
        (is (= 4 (:scroll-offset m3))))
      ;; n three times → wraps to match 0 (line 0)
      (let [m4 (-> m nav/next-search-match nav/next-search-match nav/next-search-match)]
        (is (= 0 (:search-match-idx m4)))
        (is (= 0 (:scroll-offset m4))))))

  (testing "N jumps to previous match, wrapping"
    (let [m (-> (detail-model-with-output "match\nno\nmatch")
                (assoc :mode :search :command-buf "/match")
                mode/compute-search-results
                mode/confirm-search)]
      ;; N from match 0 → wraps to match 1 (line 2)
      (is (= 1 (:search-match-idx (nav/prev-search-match m))))
      (is (= 2 (:scroll-offset (nav/prev-search-match m))))))

  (testing "n/N no-op without matches"
    (let [m (util/fresh-model)]
      (is (= m (nav/next-search-match m)))
      (is (= m (nav/prev-search-match m))))))

(deftest evidence-search-test
  (testing "Search in evidence view finds matching tree nodes"
    (let [m (-> (util/fresh-model)
                (assoc :view :evidence)
                (assoc-in [:detail :phases]
                  [{:phase :plan :status :success}
                   {:phase :implement :status :running}])
                (assoc :mode :search :command-buf "/plan")
                mode/compute-search-results)]
      (is (pos? (count (:search-matches m))))
      ;; Should match "Phases" section's "plan" entry
      (is (some #(str/includes? (:text %) "plan") (:search-matches m))))))

(deftest search-match-navigation-via-keys-test
  (testing "n key navigates to next match via update-model"
    (let [m (-> (detail-model-with-output "match\nno\nmatch\nno\nmatch")
                (assoc :mode :search :command-buf "/match")
                mode/compute-search-results
                mode/confirm-search)]
      (is (= 0 (:search-match-idx m)))
      ;; Press n → match 1
      (let [m2 (update/update-model m [:input {:key :key/n :char \n}])]
        (is (= 1 (:search-match-idx m2)))
        (is (= 2 (:scroll-offset m2))))))

  (testing "N key navigates to previous match via update-model"
    (let [m (-> (detail-model-with-output "match\nno\nmatch")
                (assoc :mode :search :command-buf "/match")
                mode/compute-search-results
                mode/confirm-search)]
      (is (= 0 (:search-match-idx m)))
      ;; Press N → wraps to last match
      (let [m2 (update/update-model m [:input {:key :key/N :char \N}])]
        (is (= 1 (:search-match-idx m2)))
        (is (= 2 (:scroll-offset m2)))))))

(deftest escape-clears-search-matches-test
  (testing "Escape cascade: search-matches cleared before go-back"
    (let [m (-> (detail-model-with-output "match here")
                (assoc :mode :search :command-buf "/match")
                mode/compute-search-results
                mode/confirm-search)]
      (is (seq (:search-matches m)))
      ;; First Escape clears search-matches
      (let [m2 (update/update-model m [:input {:key :key/escape :char (char 27)}])]
        (is (empty? (:search-matches m2)))
        (is (= :workflow-detail (:view m2)) "Stays in view, just clears matches"))
      ;; Second Escape goes back
      (let [m3 (-> m
                   (update/update-model [:input {:key :key/escape :char (char 27)}])
                   (update/update-model [:input {:key :key/escape :char (char 27)}]))]
        (is (= :workflow-list (:view m3)))))))
