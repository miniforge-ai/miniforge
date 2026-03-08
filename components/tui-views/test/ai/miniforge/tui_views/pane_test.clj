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

(ns ai.miniforge.tui-views.pane-test
  "Tests for PR-detail pane cycling, per-pane cursor positions,
   and g/G/j/k behavior in pane mode."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-views.test-util :as util]
   [ai.miniforge.tui-views.update :as update]
   [ai.miniforge.tui-views.update.pane :as pane]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn pr-detail-model
  "Create a model in :pr-detail view with pane state initialized.
   Optionally accepts a focused-pane and pane-selections override."
  ([]
   (pr-detail-model 0 {0 0, 1 0, 2 0, 3 0}))
  ([focused-pane pane-selections]
   (-> (util/fresh-model)
       (assoc :view :pr-detail)
       (assoc-in [:detail :focused-pane] focused-pane)
       (assoc-in [:detail :pane-selections] pane-selections)
       (assoc-in [:detail :selected-pr] {:pr/id 1 :pr/title "Test PR"
                                          :pr/repo "acme/repo" :pr/number 42}))))

;; ---------------------------------------------------------------------------
;; cycle-pane tests
;; ---------------------------------------------------------------------------

(deftest cycle-pane-test
  (testing "cycle-pane increments focused-pane from 0 to 1"
    (let [m (pane/cycle-pane (pr-detail-model))]
      (is (= 1 (get-in m [:detail :focused-pane])))))

  (testing "cycle-pane increments through all 5 panes"
    (let [m (-> (pr-detail-model)
                pane/cycle-pane   ;; 0 -> 1
                pane/cycle-pane   ;; 1 -> 2
                pane/cycle-pane   ;; 2 -> 3
                pane/cycle-pane)] ;; 3 -> 4
      (is (= 4 (get-in m [:detail :focused-pane])))))

  (testing "cycle-pane wraps from last pane (4) back to 0"
    (let [m (pane/cycle-pane (pr-detail-model 4 {0 0, 1 0, 2 0, 3 0}))]
      (is (= 0 (get-in m [:detail :focused-pane])))))

  (testing "cycle-pane on workflow-detail wraps at 2 panes"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-detail)
                (assoc-in [:detail :focused-pane] 1)
                pane/cycle-pane)]
      (is (= 0 (get-in m [:detail :focused-pane])))))

  (testing "cycle-pane on single-pane view stays at 0"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-list)
                (assoc-in [:detail :focused-pane] 0)
                pane/cycle-pane)]
      (is (= 0 (get-in m [:detail :focused-pane]))))))

;; ---------------------------------------------------------------------------
;; cycle-pane-reverse tests
;; ---------------------------------------------------------------------------

(deftest cycle-pane-reverse-test
  (testing "cycle-pane-reverse decrements focused-pane from 2 to 1"
    (let [m (pane/cycle-pane-reverse (pr-detail-model 2 {0 0, 1 0, 2 0, 3 0}))]
      (is (= 1 (get-in m [:detail :focused-pane])))))

  (testing "cycle-pane-reverse wraps from 0 to last pane (4)"
    (let [m (pane/cycle-pane-reverse (pr-detail-model))]
      (is (= 4 (get-in m [:detail :focused-pane])))))

  (testing "cycle-pane and cycle-pane-reverse are inverses"
    (let [m (-> (pr-detail-model 2 {0 0, 1 0, 2 0, 3 0})
                pane/cycle-pane
                pane/cycle-pane-reverse)]
      (is (= 2 (get-in m [:detail :focused-pane]))))))

;; ---------------------------------------------------------------------------
;; Per-pane selection: j/k navigate the correct pane's cursor
;; ---------------------------------------------------------------------------

(deftest pane-navigate-down-test
  (testing "j in pr-detail increments the focused pane's selection"
    (let [m (pane/pane-navigate-down (pr-detail-model))]
      (is (= 1 (get-in m [:detail :pane-selections 0])))))

  (testing "j increments selection in the focused pane only, not others"
    (let [m (-> (pr-detail-model 1 {0 5, 1 0, 2 0, 3 0})
                pane/pane-navigate-down)]
      ;; Pane 1 should have moved; pane 0 stays at 5
      (is (= 1 (get-in m [:detail :pane-selections 1])))
      (is (= 5 (get-in m [:detail :pane-selections 0]))))))

(deftest pane-navigate-up-test
  (testing "k in pr-detail decrements the focused pane's selection"
    (let [m (pane/pane-navigate-up (pr-detail-model 0 {0 3, 1 0, 2 0, 3 0}))]
      (is (= 2 (get-in m [:detail :pane-selections 0])))))

  (testing "k at selection 0 stays at 0"
    (let [m (pane/pane-navigate-up (pr-detail-model))]
      (is (= 0 (get-in m [:detail :pane-selections 0]))))))

(deftest pane-navigate-top-test
  (testing "g in pr-detail resets focused pane selection to 0"
    (let [m (pane/pane-navigate-top (pr-detail-model 0 {0 7, 1 3, 2 0, 3 0}))]
      (is (= 0 (get-in m [:detail :pane-selections 0])))
      ;; Other panes untouched
      (is (= 3 (get-in m [:detail :pane-selections 1]))))))

(deftest pane-navigate-bottom-test
  (testing "G in pr-detail sets focused pane selection to a high value"
    (let [m (pane/pane-navigate-bottom (pr-detail-model))]
      (is (= 999 (get-in m [:detail :pane-selections 0]))))))

;; ---------------------------------------------------------------------------
;; Integration: j/k/g/G through update-model in pr-detail view
;; ---------------------------------------------------------------------------

(deftest pane-navigation-integration-test
  (testing "j in pr-detail view updates pane selection via update-model"
    (let [m (update/update-model (pr-detail-model) [:input {:key :key/j :char \j}])]
      (is (= 1 (get-in m [:detail :pane-selections 0])))))

  (testing "k in pr-detail view updates pane selection via update-model"
    (let [m (-> (pr-detail-model 0 {0 3, 1 0, 2 0, 3 0})
                (update/update-model [:input {:key :key/k :char \k}]))]
      (is (= 2 (get-in m [:detail :pane-selections 0])))))

  (testing "g in pr-detail view jumps to top of focused pane"
    (let [m (-> (pr-detail-model 0 {0 5, 1 0, 2 0, 3 0})
                (update/update-model [:input {:key :key/g :char \g}]))]
      (is (= 0 (get-in m [:detail :pane-selections 0])))))

  (testing "G in pr-detail view jumps to bottom of focused pane"
    (let [m (update/update-model (pr-detail-model) [:input {:key :key/G :char \G}])]
      (is (= 999 (get-in m [:detail :pane-selections 0]))))))

;; ---------------------------------------------------------------------------
;; Entering PR detail initializes pane state
;; ---------------------------------------------------------------------------

(deftest enter-pr-detail-pane-init-test
  (testing "Entering PR detail from pr-fleet initializes focused-pane to 0"
    (let [pr-item {:pr/id 1 :pr/title "Test" :pr/repo "r" :pr/number 1}
          m (-> (util/fresh-model)
                (assoc :view :pr-fleet :pr-items [pr-item])
                (update/update-model [:input :key/enter]))]
      (is (= 0 (get-in m [:detail :focused-pane])))))

  (testing "Entering PR detail from pr-fleet initializes pane-selections map"
    (let [pr-item {:pr/id 1 :pr/title "Test" :pr/repo "r" :pr/number 1}
          m (-> (util/fresh-model)
                (assoc :view :pr-fleet :pr-items [pr-item])
                (update/update-model [:input :key/enter]))]
      (is (= {0 0, 1 0, 2 0, 3 0} (get-in m [:detail :pane-selections])))))

  (testing "Entering PR detail from train-view initializes pane state"
    (let [pr-item {:pr/id 99 :pr/title "Train PR" :pr/repo "r" :pr/number 2}
          m (-> (util/fresh-model)
                (assoc :view :train-view)
                (assoc-in [:detail :selected-train :train/prs] [pr-item])
                (update/update-model [:input :key/enter]))]
      (is (= 0 (get-in m [:detail :focused-pane])))
      (is (= {0 0, 1 0, 2 0, 3 0} (get-in m [:detail :pane-selections]))))))

;; ---------------------------------------------------------------------------
;; Tab/Shift-Tab cycles panes in pr-detail via update-model
;; ---------------------------------------------------------------------------

(deftest tab-pane-cycling-integration-test
  (testing "Tab in pr-detail cycles focused-pane forward"
    (let [m (update/update-model (pr-detail-model) [:input :key/tab])]
      (is (= 1 (get-in m [:detail :focused-pane])))))

  (testing "Shift-Tab in pr-detail cycles focused-pane backward"
    (let [m (update/update-model (pr-detail-model 2 {0 0, 1 0, 2 0, 3 0})
                                  [:input :key/shift-tab])]
      (is (= 1 (get-in m [:detail :focused-pane])))))

  (testing "Tab wraps pane in pr-detail after cycling through all 5"
    (let [m (-> (pr-detail-model)
                (update/update-model [:input :key/tab])       ;; 0->1
                (update/update-model [:input :key/tab])       ;; 1->2
                (update/update-model [:input :key/tab])       ;; 2->3
                (update/update-model [:input :key/tab])       ;; 3->4
                (update/update-model [:input :key/tab]))]     ;; 4->0
      (is (= 0 (get-in m [:detail :focused-pane]))))))
