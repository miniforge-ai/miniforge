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

(ns ai.miniforge.tui-views.selection-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-views.test-util :as util]
   [ai.miniforge.tui-views.update.selection :as sel]))

(def wf-id-1 (random-uuid))
(def wf-id-2 (random-uuid))
(def wf-id-3 (random-uuid))

(defn- three-workflows []
  (-> (util/fresh-model)
      (util/with-workflows
        [{:workflow-id wf-id-1 :name "wf-1"}
         {:workflow-id wf-id-2 :name "wf-2"}
         {:workflow-id wf-id-3 :name "wf-3"}])
      (assoc :view :workflow-list)))

;; ---------------------------------------------------------------------------
;; Space toggle selection
;; ---------------------------------------------------------------------------

(deftest toggle-selection-test
  (testing "Space selects item at cursor and advances cursor"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/space :char \space}]])]
      (is (util/selection-count-is? m 1))
      (is (= 1 (:selected-idx m)))  ;; cursor advanced
      ;; Verify the ID of the first workflow is selected
      (is (= wf-id-1 (:id (first (:workflows m)))))
      (is (contains? (:selected-ids m) wf-id-1))))

  (testing "Space deselects already-selected item"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/space :char \space}]   ;; select wf-1, cursor → 1
               [:input {:key :key/k :char \k}]           ;; cursor → 0
               [:input {:key :key/space :char \space}]])] ;; deselect wf-1
      (is (util/selection-count-is? m 0))))

  (testing "Space selects multiple items"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/space :char \space}]   ;; select wf-1, cursor → 1
               [:input {:key :key/space :char \space}]])] ;; select wf-2, cursor → 2
      (is (util/selection-count-is? m 2)))))

;; ---------------------------------------------------------------------------
;; Visual mode
;; ---------------------------------------------------------------------------

(deftest visual-mode-test
  (testing "v enters visual mode"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/v :char \v}]])]
      (is (util/visual-mode? m))
      (is (util/selection-count-is? m 1))))  ;; anchor item selected

  (testing "v + j extends selection range"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/v :char \v}]
               [:input {:key :key/j :char \j}]
               [:input {:key :key/j :char \j}]])]
      (is (util/visual-mode? m))
      (is (util/selection-count-is? m 3))))

  (testing "Escape exits visual mode but keeps selection"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/v :char \v}]
               [:input {:key :key/j :char \j}]
               [:input :key/escape]])]
      (is (not (util/visual-mode? m)))
      (is (util/selection-count-is? m 2)))))  ;; selection kept

;; ---------------------------------------------------------------------------
;; Select all / clear
;; ---------------------------------------------------------------------------

(deftest select-all-test
  (testing "a selects all items"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/a :char \a}]])]
      (is (util/selection-count-is? m 3))))

  (testing "c clears all selections"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/a :char \a}]
               [:input {:key :key/c :char \c}]])]
      (is (util/selection-count-is? m 0)))))

;; ---------------------------------------------------------------------------
;; View switch clears selection
;; ---------------------------------------------------------------------------

(deftest view-switch-clears-selection-test
  (testing "Switching view clears selection"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/a :char \a}]           ;; select all
               [:input {:key :key/d3 :char \3}]])]       ;; switch to evidence
      (is (util/selection-count-is? m 0))))

  (testing "Escape clears selection before going back"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/a :char \a}]           ;; select all
               [:input :key/escape]])]                   ;; first Escape clears selection
      (is (util/selection-count-is? m 0))
      (is (util/view-is? m :workflow-list))))             ;; still in workflow-list

  (testing "Second Escape goes back after selection is cleared"
    (let [m (util/apply-updates (three-workflows)
              [[:input :key/enter]                        ;; enter detail
               [:input :key/escape]])]                   ;; go back (no selection to clear)
      (is (util/view-is? m :workflow-list)))))

;; ---------------------------------------------------------------------------
;; Effective IDs (batch action helper)
;; ---------------------------------------------------------------------------

(deftest effective-ids-test
  (testing "Returns selected-ids when non-empty"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/space :char \space}]])]
      (is (= 1 (count (sel/effective-ids m))))))

  (testing "Falls back to cursor item when nothing selected"
    (let [m (three-workflows)]
      (is (= 1 (count (sel/effective-ids m))))
      (is (= wf-id-1 (first (sel/effective-ids m)))))))

;; ---------------------------------------------------------------------------
;; Space is context-sensitive
;; ---------------------------------------------------------------------------

(deftest space-context-sensitive-test
  (testing "Space in detail view toggles expand, not selection"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-detail)
                (util/apply-updates [[:input {:key :key/space :char \space}]]))]
      ;; should have toggled expand, not selection
      (is (contains? (get-in m [:detail :expanded-nodes]) 0))
      (is (util/selection-count-is? m 0))))

  (testing "Space in artifact-browser toggles selection"
    (let [m (-> (util/fresh-model)
                (assoc :view :artifact-browser)
                (assoc-in [:detail :artifacts] [{:type :code :name "a.clj"}
                                                {:type :test :name "b.clj"}])
                (util/apply-updates [[:input {:key :key/space :char \space}]]))]
      (is (util/selection-count-is? m 1))
      (is (contains? (:selected-ids m) [:artifact 0]))))

  (testing "Space in train-view toggles selection"
    (let [m (-> (util/fresh-model)
                (assoc :view :train-view)
                (assoc-in [:detail :selected-train :train/prs]
                          [{:pr/repo "r1" :pr/number 1 :pr/merge-order 1}
                           {:pr/repo "r1" :pr/number 2 :pr/merge-order 2}])
                (util/apply-updates [[:input {:key :key/space :char \space}]]))]
      (is (util/selection-count-is? m 1))
      (is (contains? (:selected-ids m) ["r1" 1]))))

  (testing "Space in repo-manager toggles repository selection"
    (let [m (-> (util/fresh-model)
                (assoc :view :repo-manager
                       :fleet-repos ["acme/api" "acme/web"])
                (util/apply-updates [[:input {:key :key/space :char \space}]]))]
      (is (util/selection-count-is? m 1))
      (is (contains? (:selected-ids m) "acme/api")))))

;; ---------------------------------------------------------------------------
;; Search + select (filter-aware selection)
;; ---------------------------------------------------------------------------

(deftest search-select-test
  (testing "Enter confirms search, keeps filter active"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/slash :char \/}]     ;; enter search
               [:input {:key nil :char \1}]             ;; type "1" → matches "wf-1"
               [:input :key/enter]])]                   ;; confirm search
      (is (util/mode-is? m :normal))
      (is (some? (:filtered-indices m)))                ;; filter stays!
      (is (= #{0} (:filtered-indices m)))))             ;; only wf-1 (idx 0) matches

  (testing "Escape aborts search, clears filter"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/slash :char \/}]     ;; enter search
               [:input {:key nil :char \1}]             ;; type "1"
               [:input :key/escape]])]                  ;; abort search
      (is (util/mode-is? m :normal))
      (is (nil? (:filtered-indices m)))))               ;; filter cleared

  (testing "item-id-at maps correctly with filtered-indices"
    (let [m (-> (three-workflows)
                (assoc :filtered-indices #{1 2}))]  ;; only wf-2, wf-3 visible
      ;; Cursor 0 → first visible item → wf-2
      (is (= wf-id-2 (sel/item-id-at m 0)))
      ;; Cursor 1 → second visible item → wf-3
      (is (= wf-id-3 (sel/item-id-at m 1)))))

  (testing "select-all with filtered-indices selects only visible items"
    (let [m (-> (three-workflows)
                (assoc :filtered-indices #{0 2}))    ;; wf-1, wf-3 visible
          m (sel/select-all m)]
      (is (= 2 (count (:selected-ids m))))
      (is (contains? (:selected-ids m) wf-id-1))
      (is (contains? (:selected-ids m) wf-id-3))
      (is (not (contains? (:selected-ids m) wf-id-2)))))

  (testing "Full flow: search → confirm → select-all → Esc clears filter, selection persists"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/slash :char \/}]     ;; enter search
               [:input {:key nil :char \1}]             ;; type "1" → matches wf-1
               [:input :key/enter]                      ;; confirm → filter active, normal mode
               [:input {:key :key/a :char \a}]])]       ;; select-all on filtered results
      ;; Filter is active, only 1 item selected
      (is (some? (:filtered-indices m)))
      (is (= 1 (count (:selected-ids m))))
      (is (contains? (:selected-ids m) wf-id-1))
      ;; Now Escape: clears selection first (cascade)
      (let [m2 (util/apply-updates m [[:input :key/escape]])]
        (is (= 0 (count (:selected-ids m2))))
        (is (some? (:filtered-indices m2)))             ;; filter still active
        ;; Second Escape: clears filter
        (let [m3 (util/apply-updates m2 [[:input :key/escape]])]
          (is (nil? (:filtered-indices m3)))             ;; filter cleared
          (is (util/view-is? m3 :workflow-list))))))     ;; still in list

  (testing "Space selects individual items in filtered results"
    (let [m (util/apply-updates (three-workflows)
              [[:input {:key :key/slash :char \/}]     ;; enter search
               [:input {:key nil :char \1}]             ;; type "1" → matches wf-1
               [:input :key/enter]                      ;; confirm search
               [:input {:key :key/space :char \space}]])] ;; toggle-select wf-1
      (is (= 1 (count (:selected-ids m))))
      (is (contains? (:selected-ids m) wf-id-1))))

  (testing "repo-manager select-all with filtered-indices selects only visible repos"
    (let [m (-> (util/fresh-model)
                (assoc :view :repo-manager
                       :fleet-repos ["acme/api" "acme/web" "infra/ops"]
                       :filtered-indices #{0 2}))
          m (sel/select-all m)]
      (is (= #{"acme/api" "infra/ops"} (:selected-ids m)))
      (is (= 2 (count (:selected-ids m)))))))
