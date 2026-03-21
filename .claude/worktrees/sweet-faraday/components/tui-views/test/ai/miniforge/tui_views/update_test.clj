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

(ns ai.miniforge.tui-views.update-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-sync.interface :as pr-sync]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.test-util :as util]
   [ai.miniforge.tui-views.update :as update]))

(def wf-id-1 (random-uuid))
(def wf-id-2 (random-uuid))

(defn two-workflows
  "Create a model with 2 workflows in the workflow-list view."
  []
  (-> (util/fresh-model)
      (util/with-workflows [{:workflow-id wf-id-1 :name "wf-1"}
                            {:workflow-id wf-id-2 :name "wf-2"}])
      (assoc :view :workflow-list)))

(deftest navigation-test
  (testing "j moves down in workflow list"
    (let [m (util/apply-updates (two-workflows) [[:input {:key :key/j :char \j}]])]
      (is (util/selected-idx-is? m 1))))

  (testing "k moves up in workflow list"
    (let [m (util/apply-updates (two-workflows)
              [[:input {:key :key/j :char \j}]
               [:input {:key :key/k :char \k}]])]
      (is (util/selected-idx-is? m 0))))

  (testing "k at top stays at 0"
    (let [m (util/apply-updates (two-workflows) [[:input {:key :key/k :char \k}]])]
      (is (util/selected-idx-is? m 0))))

  (testing "j at bottom stays at max"
    (let [m (util/apply-updates (two-workflows)
              [[:input {:key :key/j :char \j}]
               [:input {:key :key/j :char \j}]
               [:input {:key :key/j :char \j}]])]
      (is (util/selected-idx-is? m 1))))

  (testing "g goes to top"
    (let [m (util/apply-updates (two-workflows)
              [[:input {:key :key/j :char \j}]
               [:input {:key :key/g :char \g}]])]
      (is (util/selected-idx-is? m 0))))

  (testing "G goes to bottom"
    (let [m (util/apply-updates (two-workflows) [[:input {:key :key/G :char \G}]])]
      (is (util/selected-idx-is? m 1)))))

(deftest view-navigation-test
  (testing "Enter from workflow-list drills into workflow detail"
    (let [m (util/apply-updates
              (assoc (two-workflows) :view :workflow-list)
              [[:input :key/enter]])]
      (is (util/view-is? m :workflow-detail))
      (is (= wf-id-1 (get-in m [:detail :workflow-id])))))

  (testing "Workflow detail entry uses the row detail snapshot when present"
    (let [m (-> (two-workflows)
                (assoc-in [:workflows 0 :detail-snapshot]
                          {:workflow-id wf-id-1
                           :phases [{:phase :plan :status :success}
                                    {:phase :implement :status :running}]
                           :agent-output "streamed output"})
                (assoc :view :workflow-list)
                (update/update-model [:input :key/enter]))]
      (is (= 2 (count (get-in m [:detail :phases]))))
      (is (= "streamed output" (get-in m [:detail :agent-output])))))

  (testing "Escape from workflow-detail returns to workflow-list"
    (let [m (util/apply-updates
              (assoc (two-workflows) :view :workflow-list)
              [[:input :key/enter]
               [:input :key/escape]])]
      (is (util/view-is? m :workflow-list))))

  (testing "Number keys switch top-level views (1-6)"
    (is (util/view-is? (update/update-model (util/fresh-model) [:input {:key :key/d1 :char \1}]) :pr-fleet))
    (is (util/view-is? (update/update-model (util/fresh-model) [:input {:key :key/d2 :char \2}]) :workflow-list))
    (is (util/view-is? (update/update-model (util/fresh-model) [:input {:key :key/d3 :char \3}]) :evidence))
    (is (util/view-is? (update/update-model (util/fresh-model) [:input {:key :key/d4 :char \4}]) :artifact-browser))
    (is (util/view-is? (update/update-model (util/fresh-model) [:input {:key :key/d5 :char \5}]) :dag-kanban))
    (is (util/view-is? (update/update-model (util/fresh-model) [:input {:key :key/d6 :char \6}]) :repo-manager)))

  (testing "Number keys above available top-level count are no-ops"
    (let [m (util/fresh-model)]
      (is (util/view-is? (update/update-model m [:input {:key :key/d7 :char \7}]) :pr-fleet))
      (is (util/view-is? (update/update-model m [:input {:key :key/d8 :char \8}]) :pr-fleet))
      (is (util/view-is? (update/update-model m [:input {:key :key/d9 :char \9}]) :pr-fleet))
      (is (util/view-is? (update/update-model m [:input {:key :key/d0 :char \0}]) :pr-fleet)))))

(deftest workflow-events-test
  (testing "Workflow added appears in list"
    (let [m (update/update-model (util/fresh-model)
              [:msg/workflow-added {:workflow-id wf-id-1 :name "test-wf"}])]
      (is (util/workflow-count-is? m 1))
      (is (= "test-wf" (:name (first (:workflows m)))))))

  (testing "Phase changed updates workflow"
    (let [m (util/apply-updates (util/fresh-model)
              [[:msg/workflow-added {:workflow-id wf-id-1 :name "test"}]
               [:msg/phase-changed {:workflow-id wf-id-1 :phase :implement}]])]
      (is (util/workflow-has-phase? m 0 :implement))))

  (testing "Workflow done updates status"
    (let [m (util/apply-updates (util/fresh-model)
              [[:msg/workflow-added {:workflow-id wf-id-1 :name "test"}]
               [:msg/workflow-done {:workflow-id wf-id-1 :status :success}]])]
      (is (util/workflow-has-status? m 0 :success))
      (is (= 100 (get-in m [:workflows 0 :progress]))))))

  (testing "Agent started message is routed through the root update"
    (let [m (util/apply-updates (util/fresh-model)
              [[:msg/workflow-added {:workflow-id wf-id-1 :name "test"}]
               [:msg/agent-started {:workflow-id wf-id-1 :agent :planner}]])]
      (is (= :started (get-in m [:workflows 0 :agents :planner :status])))))

(deftest mode-switching-test
  (testing "Colon enters command mode"
    (let [m (util/apply-updates (util/fresh-model) [[:input {:key :key/colon :char \:}]])]
      (is (util/mode-is? m :command))
      (is (= ":" (:command-buf m)))))

  (testing "Slash enters search mode"
    (let [m (util/apply-updates (util/fresh-model) [[:input {:key :key/slash :char \/}]])]
      (is (util/mode-is? m :search))
      (is (= "/" (:command-buf m)))))

  (testing "Escape exits mode"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input {:key :key/colon :char \:}]
               [:input :key/escape]])]
      (is (util/mode-is? m :normal))
      (is (= "" (:command-buf m))))))

;; ---------------------------------------------------------------------------
;; New tests: keybindings, help overlay, gate results, PR navigation
;; ---------------------------------------------------------------------------

(deftest new-keybinding-test
  (testing "r key triggers local refresh outside PR/repo views"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-list)
                (update/update-model [:input {:key :key/r :char \r}]))]
      (is (= "Refreshed" (:flash-message m)))
      (is (some? (:last-updated m)))))

  (testing "r key in pr-fleet triggers PR sync side-effect"
    (let [m (-> (util/fresh-model)
                (assoc :view :pr-fleet)
                (update/update-model [:input {:key :key/r :char \r}]))]
      (is (= :sync-prs (:type (:side-effect m))))
      (is (str/includes? (:flash-message m) "Syncing"))))

  (testing "s key in pr-fleet also triggers PR sync side-effect"
    (let [m (-> (util/fresh-model)
                (assoc :view :pr-fleet)
                (update/update-model [:input {:key :key/s :char \s}]))]
      (is (= :sync-prs (:type (:side-effect m))))
      (is (str/includes? (:flash-message m) "Syncing"))))

  (testing "b key switches to dag-kanban view"
    (let [m (update/update-model (util/fresh-model) [:input {:key :key/b :char \b}])]
      (is (util/view-is? m :dag-kanban))
      (is (util/selected-idx-is? m 0))))

  (testing "e key switches to evidence view"
    (let [m (update/update-model (util/fresh-model) [:input {:key :key/e :char \e}])]
      (is (util/view-is? m :evidence))
      (is (util/selected-idx-is? m 0))))

  (testing "a key selects all items after Space (gated behind first selection)"
    (let [m (util/apply-updates (two-workflows)
              [[:input {:key :key/space :char \space}]  ;; select first
               [:input {:key :key/a :char \a}]])]
      (is (util/selection-count-is? m 2))))

  (testing "? key toggles help overlay on"
    (let [m (update/update-model (util/fresh-model) [:input {:key :key/question :char \?}])]
      (is (true? (:help-visible? m)))))

  (testing "Space key toggles expand in detail view"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-detail)
                (util/apply-updates [[:input {:key :key/space :char \space}]]))]
      (is (contains? (get-in m [:detail :expanded-nodes]) 0))))

  (testing "Space key toggles expand off when pressed again in detail view"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-detail)
                (util/apply-updates [[:input {:key :key/space :char \space}]
                                     [:input {:key :key/space :char \space}]]))]
      (is (not (contains? (get-in m [:detail :expanded-nodes]) 0)))))

  (testing "6 key switches to repo-manager view"
    (let [m (update/update-model (util/fresh-model) [:input {:key :key/d6 :char \6}])]
      (is (util/view-is? m :repo-manager))
      (is (util/selected-idx-is? m 0))))

  (testing "In workflow detail context, number keys switch within detail subviews"
    (let [m (-> (two-workflows)
                (update/update-model [:input :key/enter])             ;; workflow-detail
                (update/update-model [:input {:key :key/d2 :char \2}]))] ;; evidence
      (is (util/view-is? m :evidence))
      (let [m2 (update/update-model m [:input {:key :key/d3 :char \3}])]
        (is (util/view-is? m2 :artifact-browser))))))

(deftest repo-manager-actions-test
  (testing "b opens remote browse mode and triggers browse side-effect"
    (let [m (-> (util/fresh-model)
                (assoc :view :repo-manager)
                (update/update-model [:input {:key :key/b :char \b}]))]
      (is (= :browse (:repo-manager-source m)))
      (is (= {:type :browse-repos :source :repo-manager :provider :all} (:side-effect m)))
      (is (true? (:browse-repos-loading? m)))))

  (testing "repos-browsed from repo-manager source enters browse mode"
    (let [m (-> (util/fresh-model)
                (assoc :view :repo-manager
                       :fleet-repos ["acme/already"])
                (update/update-model [:msg/repos-browsed {:success? true
                                                          :source :repo-manager
                                                          :repos ["acme/already" "acme/new"]}]))]
      (is (= :browse (:repo-manager-source m)))
      ;; already-configured repo is excluded from browse candidates
      (is (= ["acme/new"] (model/browse-candidate-repos m)))))

  (testing "Enter in browse mode adds selected remote repo to fleet"
    (with-redefs [pr-sync/add-repo! (fn [_repo] {:success? true
                                                 :added? true
                                                 :repo "acme/new"
                                                 :repos ["acme/new"]})]
      (let [m (-> (util/fresh-model)
                  (assoc :view :repo-manager
                         :repo-manager-source :browse
                         :browse-repos ["acme/new"])
                  (update/update-model [:input :key/enter]))]
        (is (= ["acme/new"] (:fleet-repos m)))
        (is (= {:type :sync-prs} (:side-effect m)))
        (is (str/includes? (:flash-message m) "Syncing")))))

  (testing "d requests repo removal confirm in fleet mode; y removes"
    (with-redefs [pr-sync/remove-repo! (fn [_repo] {:success? true
                                                    :removed? true
                                                    :repo "acme/one"
                                                    :repos []})]
      (let [m (-> (util/fresh-model)
                  (assoc :view :repo-manager
                         :repo-manager-source :fleet
                         :fleet-repos ["acme/one"])
                  (update/update-model [:input {:key :key/d :char \d}]))]
        (is (= :remove-repos (get-in m [:confirm :action])))
        (is (= #{"acme/one"} (get-in m [:confirm :ids])))
        (let [m2 (update/update-model m [:input {:key :key/y :char \y}])]
          (is (nil? (:confirm m2)))
          (is (empty? (:fleet-repos m2)))
          (is (str/includes? (:flash-message m2) "Removed")))))))

(deftest help-overlay-dismissal-test
  (testing "? toggles help on then off"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input {:key :key/question :char \?}]
               [:input {:key :key/question :char \?}]])]
      (is (false? (:help-visible? m)))))

  (testing "Escape dismisses help overlay"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input {:key :key/question :char \?}]
               [:input :key/escape]])]
      (is (false? (:help-visible? m)))))

  (testing "Navigation keys blocked while help is visible"
    (let [m (util/apply-updates (two-workflows)
              [[:input {:key :key/question :char \?}]
               [:input {:key :key/j :char \j}]])]
      (is (util/selected-idx-is? m 0))
      (is (true? (:help-visible? m)))))

  (testing "q still quits even with help visible"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input {:key :key/question :char \?}]
               [:input {:key :key/q :char \q}]])]
      (is (true? (:quit? m))))))

(deftest gate-result-event-test
  (testing "Passing gate result is stored on workflow"
    (let [m (util/apply-updates (util/fresh-model)
              [[:msg/workflow-added {:workflow-id wf-id-1 :name "gated"}]
               [:msg/gate-result {:workflow-id wf-id-1 :gate :lint :passed? true}]])]
      (is (= 1 (count (get-in m [:workflows 0 :gate-results]))))
      (is (nil? (:flash-message m)))))

  (testing "Failing gate result sets flash message"
    (let [m (util/apply-updates (util/fresh-model)
              [[:msg/workflow-added {:workflow-id wf-id-1 :name "gated"}]
               [:msg/gate-result {:workflow-id wf-id-1 :gate :security :passed? false}]])]
      (is (= 1 (count (get-in m [:workflows 0 :gate-results]))))
      (is (str/includes? (:flash-message m) "FAILED"))))

  (testing "Gate result includes timestamp"
    (let [m (util/apply-updates (util/fresh-model)
              [[:msg/workflow-added {:workflow-id wf-id-1 :name "gated"}]
               [:msg/gate-result {:workflow-id wf-id-1 :gate :test :passed? true}]])]
      (is (some? (:last-updated m))))))

(deftest pr-navigation-test
  (testing "Enter from pr-fleet drills into pr-detail"
    (let [pr-item {:pr/id 42 :pr/title "Fix the thing" :pr/readiness 0.8}
          m (-> (util/fresh-model)
                (assoc :view :pr-fleet)
                (assoc :pr-items [pr-item])
                (update/update-model [:input :key/enter]))]
      (is (util/view-is? m :pr-detail))
      (is (= pr-item (get-in m [:detail :selected-pr])))))

  (testing "Escape from pr-detail returns to pr-fleet"
    (let [m (-> (util/fresh-model)
                (assoc :view :pr-detail)
                (update/update-model [:input :key/escape]))]
      (is (util/view-is? m :pr-fleet))))

  (testing "Escape from train-view returns to pr-fleet"
    (let [m (-> (util/fresh-model)
                (assoc :view :train-view)
                (update/update-model [:input :key/escape]))]
      (is (util/view-is? m :pr-fleet))))

  (testing "Escape from evidence in detail context returns to workflow-list"
    (let [m (-> (util/fresh-model)
                (assoc :view :evidence)
                (assoc-in [:detail :workflow-id] wf-id-1)
                (update/update-model [:input :key/escape]))]
      (is (util/view-is? m :workflow-list))))

  (testing "Escape from evidence as top-level aggregate is a no-op"
    (let [m (-> (util/fresh-model)
                (assoc :view :evidence)
                (update/update-model [:input :key/escape]))]
      (is (util/view-is? m :evidence))))

  (testing "Enter from train-view drills into pr-detail"
    (let [pr-item {:pr/id 99 :pr/title "Train PR"}
          m (-> (util/fresh-model)
                (assoc :view :train-view)
                (assoc-in [:detail :selected-train :train/prs] [pr-item])
                (update/update-model [:input :key/enter]))]
      (is (util/view-is? m :pr-detail))
      (is (= pr-item (get-in m [:detail :selected-pr]))))))

;; ---------------------------------------------------------------------------
;; Search/command mode with mapped letters — regression test for letter capture
;; ---------------------------------------------------------------------------

(deftest search-mode-mapped-letters-test
  (testing "Typing /release in search mode captures all letters"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input {:key :key/slash :char \/}]    ;; enter search mode
               [:input {:key :key/r :char \r}]        ;; mapped letter
               [:input {:key :key/e :char \e}]        ;; mapped letter
               [:input {:key :key/l :char \l}]        ;; mapped letter
               [:input {:key :key/e :char \e}]        ;; mapped letter
               [:input {:key :key/a :char \a}]        ;; mapped letter
               [:input {:key nil :char \s}]            ;; unmapped letter
               [:input {:key :key/e :char \e}]])]     ;; mapped letter
      (is (util/mode-is? m :search))
      (is (= "/release" (:command-buf m)))))

  (testing "Typing :theme dark in command mode captures all letters"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input {:key :key/colon :char \:}]     ;; enter command mode
               [:input {:key nil :char \t}]             ;; unmapped
               [:input {:key :key/h :char \h}]          ;; mapped
               [:input {:key :key/e :char \e}]          ;; mapped
               [:input {:key nil :char \m}]             ;; unmapped
               [:input {:key :key/e :char \e}]          ;; mapped
               [:input {:key :key/space :char \space}]  ;; space
               [:input {:key nil :char \d}]             ;; unmapped
               [:input {:key :key/a :char \a}]          ;; mapped
               [:input {:key :key/r :char \r}]          ;; mapped
               [:input {:key :key/k :char \k}]])]       ;; mapped
      (is (util/mode-is? m :command))
      (is (= ":theme dark" (:command-buf m)))))

  (testing "Escape exits search mode and clears buffer"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input {:key :key/slash :char \/}]
               [:input {:key :key/r :char \r}]
               [:input :key/escape]])]
      (is (util/mode-is? m :normal))
      (is (= "" (:command-buf m))))))

(deftest workflow-detail-scroll-test
  (testing "j/k in workflow-detail scroll agent output (not selected-idx)"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-detail)
                (assoc-in [:detail :agent-output] "line1\nline2\nline3\nline4\nline5")
                (update/update-model [:input {:key :key/j :char \j}]))]
      ;; scroll-offset should increase, selected-idx stays 0
      (is (= 1 (:scroll-offset m)))
      (is (= 0 (:selected-idx m)))))

  (testing "k decreases scroll-offset in workflow-detail"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-detail :scroll-offset 5)
                (update/update-model [:input {:key :key/k :char \k}]))]
      (is (= 4 (:scroll-offset m)))))

  (testing "k at scroll-offset 0 stays at 0"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-detail :scroll-offset 0)
                (update/update-model [:input {:key :key/k :char \k}]))]
      (is (= 0 (:scroll-offset m))))))

(deftest tab-navigation-test
  (testing "Tab in workflow-detail (with context) cycles to evidence sub-view"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-detail)
                (assoc-in [:detail :workflow-id] wf-id-1)
                (update/update-model [:input :key/tab]))]
      (is (util/view-is? m :evidence))))

  (testing "Tab stays within detail sub-views — does not leak to aggregate tier"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-detail)
                (assoc-in [:detail :workflow-id] wf-id-1)
                (update/update-model [:input :key/tab])    ;; → evidence (sub-view)
                (update/update-model [:input :key/tab])    ;; → artifact-browser (sub-view)
                (update/update-model [:input :key/tab]))]  ;; → workflow-detail (wraps)
      ;; Tab cycles within detail sub-views only; Esc goes back to aggregate
      (is (util/view-is? m :workflow-detail))))

  (testing "Tab in workflow-detail without context falls through to detail pane cycling"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-detail)
                ;; No workflow-id → not in detail subview context
                (update/update-model [:input :key/tab]))]
      ;; workflow-detail is in detail-views, so cycle-pane is called
      (is (util/view-is? m :workflow-detail))
      (is (= 1 (get-in m [:detail :focused-pane])))))

  (testing "Tab in workflow-list cycles to next top-level view (evidence)"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-list)
                (update/update-model [:input :key/tab]))]
      (is (util/view-is? m :evidence))))

  (testing "Tab in dag-kanban cycles to repo-manager"
    (let [m (-> (util/fresh-model)
                (assoc :view :dag-kanban)
                (update/update-model [:input :key/tab]))]
      (is (util/view-is? m :repo-manager))))

  (testing "Tab in repo-manager wraps to pr-fleet"
    (let [m (-> (util/fresh-model)
                (assoc :view :repo-manager)
                (update/update-model [:input :key/tab]))]
      (is (util/view-is? m :pr-fleet))))

  (testing "Tab cycles through all top-level views and wraps"
    (let [m (-> (util/fresh-model)
                (assoc :view :pr-fleet)
                (update/update-model [:input :key/tab])    ;; → workflow-list
                (update/update-model [:input :key/tab])    ;; → evidence
                (update/update-model [:input :key/tab])    ;; → artifact-browser
                (update/update-model [:input :key/tab])    ;; → dag-kanban
                (update/update-model [:input :key/tab])    ;; → repo-manager
                (update/update-model [:input :key/tab]))]  ;; → pr-fleet (wrap)
      (is (util/view-is? m :pr-fleet)))))

(deftest shift-tab-navigation-test
  (testing "Shift+Tab in workflow-list cycles to previous top-level view"
    (let [m (-> (util/fresh-model)
                (assoc :view :workflow-list)
                (update/update-model [:input :key/shift-tab]))]
      (is (util/view-is? m :pr-fleet))))

  (testing "Shift+Tab in pr-fleet wraps to repo-manager"
    (let [m (-> (util/fresh-model)
                (assoc :view :pr-fleet)
                (update/update-model [:input :key/shift-tab]))]
      (is (util/view-is? m :repo-manager))))

  (testing "Shift+Tab in workflow detail subview cycles reverse within sub-views"
    (let [m (-> (util/fresh-model)
                (assoc :view :evidence)
                (assoc-in [:detail :workflow-id] wf-id-1)
                (update/update-model [:input :key/shift-tab]))]
      (is (util/view-is? m :workflow-detail)))))

;; ---------------------------------------------------------------------------
;; Regression: Tab after going back from detail stays at aggregate tier
;; ---------------------------------------------------------------------------

(deftest tab-after-go-back-regression-test
  (testing "Tab on workflow-list after Esc from detail stays at aggregate tier"
    (let [m (-> (two-workflows)
                (update/update-model [:input :key/enter])   ;; drill into wf-1 detail
                (update/update-model [:input :key/escape])  ;; back to workflow-list
                (update/update-model [:input :key/tab]))]   ;; Tab should cycle top-level
      ;; Should go to evidence as top-level aggregate, NOT enter detail subview
      (is (util/view-is? m :evidence))
      ;; workflow-id should be cleared so we're not in detail context
      (is (nil? (get-in m [:detail :workflow-id])))))

  (testing "Tab on evidence after Esc from detail sub-view cycles top-level"
    (let [m (-> (two-workflows)
                (update/update-model [:input :key/enter])   ;; drill into wf-1 detail
                (update/update-model [:input :key/tab])     ;; → evidence (sub-view)
                (update/update-model [:input :key/escape])  ;; back to workflow-list
                (update/update-model [:input :key/tab]))]   ;; Tab: top-level cycle
      (is (util/view-is? m :evidence))
      (is (nil? (get-in m [:detail :workflow-id])))))

  (testing "Tab cycles all top-level views cleanly after detail visit"
    (let [m (-> (two-workflows)
                (update/update-model [:input :key/enter])   ;; drill in
                (update/update-model [:input :key/escape])  ;; back out
                ;; Now cycle through all top-level views
                (update/update-model [:input :key/tab])     ;; → evidence
                (update/update-model [:input :key/tab])     ;; → artifact-browser
                (update/update-model [:input :key/tab])     ;; → dag-kanban
                (update/update-model [:input :key/tab])     ;; → repo-manager
                (update/update-model [:input :key/tab])     ;; → pr-fleet
                (update/update-model [:input :key/tab]))]   ;; → workflow-list (wrap)
      (is (util/view-is? m :workflow-list)))))

;; ---------------------------------------------------------------------------
;; Detail sibling navigation — left/right arrows
;; ---------------------------------------------------------------------------

(deftest detail-sibling-navigation-test
  (testing "Right arrow in workflow-detail moves to next workflow"
    (let [m (-> (two-workflows)
                (update/update-model [:input :key/enter])  ;; enter wf-1 detail
                (update/update-model [:input :key/right]))]
      (is (util/view-is? m :workflow-detail))
      (is (= wf-id-2 (get-in m [:detail :workflow-id])))))

  (testing "Left arrow in workflow-detail moves to previous workflow"
    (let [m (-> (two-workflows)
                ;; Navigate to wf-2 detail
                (update/update-model [:input {:key :key/j :char \j}])  ;; cursor → 1
                (update/update-model [:input :key/enter])              ;; enter wf-2
                (update/update-model [:input :key/left]))]             ;; ← back to wf-1
      (is (util/view-is? m :workflow-detail))
      (is (= wf-id-1 (get-in m [:detail :workflow-id])))))

  (testing "Left arrow at first item is a no-op"
    (let [m (-> (two-workflows)
                (update/update-model [:input :key/enter])   ;; enter wf-1 detail
                (update/update-model [:input :key/left]))]  ;; ← at boundary
      (is (util/view-is? m :workflow-detail))
      (is (= wf-id-1 (get-in m [:detail :workflow-id])))))

  (testing "Right arrow at last item is a no-op"
    (let [m (-> (two-workflows)
                (update/update-model [:input {:key :key/j :char \j}])  ;; cursor → 1
                (update/update-model [:input :key/enter])              ;; enter wf-2
                (update/update-model [:input :key/right]))]            ;; → at boundary
      (is (util/view-is? m :workflow-detail))
      (is (= wf-id-2 (get-in m [:detail :workflow-id])))))

  (testing "Right arrow in pr-detail moves to next PR"
    (let [pr-1 {:pr/id 1 :pr/title "PR One"}
          pr-2 {:pr/id 2 :pr/title "PR Two"}
          m (-> (util/fresh-model)
                (assoc :view :pr-fleet)
                (assoc :pr-items [pr-1 pr-2])
                (update/update-model [:input :key/enter])   ;; enter pr-1 detail
                (update/update-model [:input :key/right]))]  ;; → pr-2
      (is (util/view-is? m :pr-detail))
      (is (= pr-2 (get-in m [:detail :selected-pr])))))

  (testing "Left/right arrows in list views are no-ops"
    (let [m (-> (two-workflows)
                (update/update-model [:input :key/right]))]
      (is (util/view-is? m :workflow-list))
      (is (util/selected-idx-is? m 0)))))

;; ---------------------------------------------------------------------------
;; Batch command tests — archive, delete, cancel, rerun via command mode
;; ---------------------------------------------------------------------------

(defn execute-command
  "Apply a command string to model: enters command mode, types it, presses Enter."
  [model cmd-str]
  (util/apply-updates model
    (into [[:input {:key :key/colon :char \:}]]   ;; enter command mode
          (conj (vec (for [ch cmd-str]
                       [:input {:key nil :char ch}]))
                [:input :key/enter]))))             ;; execute

(deftest batch-archive-test
  (testing ":archive with selection triggers confirmation"
    (let [m (-> (two-workflows)
                (util/apply-updates [[:input {:key :key/space :char \space}]])  ;; select wf-1
                (execute-command "archive"))]
      (is (util/confirm-active? m))
      (is (= :archive (get-in m [:confirm :action])))
      (is (= 1 (count (get-in m [:confirm :ids]))))))

  (testing "y confirms archive — sets status to :archived"
    (let [m (-> (two-workflows)
                (util/apply-updates [[:input {:key :key/space :char \space}]])
                (execute-command "archive")
                (update/update-model [:input {:key :key/y :char \y}]))]
      (is (not (util/confirm-active? m)))
      (is (util/workflow-has-status? m 0 :archived))
      (is (str/includes? (:flash-message m) "Archived"))))

  (testing "n cancels confirmation"
    (let [m (-> (two-workflows)
                (util/apply-updates [[:input {:key :key/space :char \space}]])
                (execute-command "archive")
                (update/update-model [:input {:key :key/n :char \n}]))]
      (is (not (util/confirm-active? m)))
      (is (= "Cancelled" (:flash-message m)))
      ;; Workflow status unchanged (still :running from default)
      (is (not= :archived (get-in m [:workflows 0 :status])))))

  (testing "Escape cancels confirmation"
    (let [m (-> (two-workflows)
                (util/apply-updates [[:input {:key :key/space :char \space}]])
                (execute-command "archive")
                (update/update-model [:input :key/escape]))]
      (is (not (util/confirm-active? m)))
      (is (= "Cancelled" (:flash-message m))))))

(deftest batch-delete-test
  (testing ":delete with selection triggers confirmation"
    (let [m (-> (two-workflows)
                (util/apply-updates [[:input {:key :key/space :char \space}]   ;; select first
                                     [:input {:key :key/a :char \a}]])         ;; select all
                (execute-command "delete"))]
      (is (util/confirm-active? m))
      (is (= :delete (get-in m [:confirm :action])))
      (is (= 2 (count (get-in m [:confirm :ids]))))))

  (testing "y confirms delete — removes workflows"
    (let [m (-> (two-workflows)
                (util/apply-updates [[:input {:key :key/space :char \space}]
                                     [:input {:key :key/a :char \a}]])
                (execute-command "delete")
                (update/update-model [:input {:key :key/y :char \y}]))]
      (is (not (util/confirm-active? m)))
      (is (util/workflow-count-is? m 0))
      (is (str/includes? (:flash-message m) "Deleted")))))

(deftest batch-cancel-test
  (testing ":cancel with running workflow triggers confirmation"
    (let [m (-> (two-workflows)
                (util/apply-updates [[:input {:key :key/space :char \space}]])
                (execute-command "cancel"))]
      (is (util/confirm-active? m))
      (is (= :cancel (get-in m [:confirm :action])))))

  (testing "y confirms cancel — sets running workflow to :cancelled"
    (let [m (-> (two-workflows)
                (util/apply-updates [[:input {:key :key/space :char \space}]])
                (execute-command "cancel")
                (update/update-model [:input {:key :key/y :char \y}]))]
      (is (not (util/confirm-active? m)))
      (is (util/workflow-has-status? m 0 :cancelled))
      (is (str/includes? (:flash-message m) "Cancelled")))))

(deftest batch-rerun-test
  (testing ":rerun is immediate — no confirmation prompt"
    (let [m (-> (two-workflows)
                ;; Set first workflow to :failed so rerun applies
                (assoc-in [:workflows 0 :status] :failed)
                (util/apply-updates [[:input {:key :key/space :char \space}]])
                (execute-command "rerun"))]
      (is (not (util/confirm-active? m)))
      (is (util/workflow-has-status? m 0 :pending))
      (is (str/includes? (:flash-message m) "Rerunning"))))

  (testing ":rerun with no failed workflows shows message"
    (let [m (-> (two-workflows)
                (util/apply-updates [[:input {:key :key/space :char \space}]])
                (execute-command "rerun"))]
      ;; Running workflows aren't eligible for rerun, so status unchanged
      (is (not (util/confirm-active? m)))
      (is (str/includes? (:flash-message m) "Rerunning")))))

;; ---------------------------------------------------------------------------
;; PR event handler tests
;; ---------------------------------------------------------------------------

(deftest pr-synced-test
  (testing ":msg/prs-synced replaces pr-items"
    (let [prs [{:pr/repo "r1" :pr/number 1 :pr/title "First"}
               {:pr/repo "r2" :pr/number 2 :pr/title "Second"}]
          m (update/update-model (util/fresh-model)
              [:msg/prs-synced {:pr-items prs}])]
      (is (= 2 (count (:pr-items m))))
      (is (str/includes? (:flash-message m) "Synced"))
      (is (str/includes? (:flash-message m) "2 PRs"))))

  (testing ":msg/prs-synced with empty list clears items"
    (let [m (-> (util/fresh-model)
                (assoc :pr-items [{:pr/repo "r1" :pr/number 1}])
                (update/update-model [:msg/prs-synced {:pr-items []}]))]
      (is (= 0 (count (:pr-items m)))))))

(deftest repos-browsed-test
  (testing ":msg/repos-browsed success caches remote repos"
    (let [m (-> (util/fresh-model)
                (assoc :browse-repos-loading? true)
                (update/update-model
                  [:msg/repos-browsed {:success? true
                                       :repos ["acme/repo1" "acme/repo2"]}]))]
      (is (= ["acme/repo1" "acme/repo2"] (:browse-repos m)))
      (is (false? (:browse-repos-loading? m)))
      (is (str/includes? (:flash-message m) "Loaded 2 remote repo(s)"))))

  (testing ":msg/repos-browsed failure clears loading and sets error flash"
    (let [m (-> (util/fresh-model)
                (assoc :browse-repos-loading? true)
                (update/update-model
                  [:msg/repos-browsed {:success? false :error "auth failed"}]))]
      (is (false? (:browse-repos-loading? m)))
      (is (str/includes? (:flash-message m) "Repo browse failed"))
      (is (str/includes? (:flash-message m) "auth failed"))))

  (testing ":msg/repos-browsed failure includes source and no-details fallback"
    (let [m (-> (util/fresh-model)
                (assoc :browse-repos-loading? true)
                (update/update-model
                  [:msg/repos-browsed {:success? false
                                       :error-source :graphql
                                       :error nil}]))]
      (is (false? (:browse-repos-loading? m)))
      (is (str/includes? (:flash-message m) "(graphql)"))
      (is (str/includes? (:flash-message m) "no error details"))))

  (testing ":msg/repos-browsed refreshes active :add-repo completion popup"
    (with-redefs [pr-sync/get-configured-repos (fn [] [])]
      (let [m (-> (util/fresh-model)
                  (assoc :mode :command
                         :command-buf ":add-repo "
                         :completing? true
                         :completions ["browse"]
                         :completion-idx 0
                         :browse-repos-loading? true)
                  (update/update-model
                    [:msg/repos-browsed {:success? true
                                         :repos ["acme/new-repo"]}]))]
        (is (false? (:browse-repos-loading? m)))
        (is (true? (:completing? m)))
        (is (some #{"acme/new-repo"} (:completions m)))))))

(deftest pr-updated-test
  (testing ":msg/pr-updated merges into existing PR"
    (let [m (-> (util/fresh-model)
                (assoc :pr-items [{:pr/repo "r1" :pr/number 1 :pr/title "Old"
                                   :pr/status :open}])
                (update/update-model
                  [:msg/pr-updated {:pr/repo "r1" :pr/number 1
                                    :pr/status :merge-ready}]))]
      (is (= :merge-ready (get-in m [:pr-items 0 :pr/status])))
      (is (= "Old" (get-in m [:pr-items 0 :pr/title]))))))

(deftest pr-removed-test
  (testing ":msg/pr-removed removes matching PR"
    (let [m (-> (util/fresh-model)
                (assoc :pr-items [{:pr/repo "r1" :pr/number 1 :pr/title "A"}
                                  {:pr/repo "r2" :pr/number 2 :pr/title "B"}])
                (update/update-model
                  [:msg/pr-removed {:pr/repo "r1" :pr/number 1}]))]
      (is (= 1 (count (:pr-items m))))
      (is (= "B" (get-in m [:pr-items 0 :pr/title]))))))

(deftest side-effect-error-test
  (testing ":msg/side-effect-error sets flash message"
    (let [m (update/update-model (util/fresh-model)
              [:msg/side-effect-error {:type :sync-prs :error "Network timeout"}])]
      (is (str/includes? (:flash-message m) "Effect error"))
      (is (str/includes? (:flash-message m) "sync-prs"))
      (is (str/includes? (:flash-message m) "Network timeout"))))

  (testing ":msg/side-effect-error for browse clears browse loading flag"
    (let [m (-> (util/fresh-model)
                (assoc :browse-repos-loading? true)
                (update/update-model
                  [:msg/side-effect-error {:type :browse-repos
                                           :error "Rate limited"}]))]
      (is (false? (:browse-repos-loading? m)))
      (is (str/includes? (:flash-message m) "browse-repos"))
      (is (str/includes? (:flash-message m) "Rate limited")))))

(deftest sync-command-sets-side-effect-test
  (testing ":sync command sets :side-effect on model"
    (let [m (execute-command (util/fresh-model) "sync")]
      (is (= :sync-prs (:type (:side-effect m))))
      (is (str/includes? (:flash-message m) "Syncing")))))

;; ---------------------------------------------------------------------------
;; Workflow detail entry fires reload side-effect
;; ---------------------------------------------------------------------------

(deftest enter-workflow-detail-fires-reload-side-effect-test
  (testing "entering workflow detail fires reload-workflow-detail side-effect"
    (let [m (-> (two-workflows)
                (assoc :view :workflow-list)
                (update/update-model [:input :key/enter]))]
      (is (util/view-is? m :workflow-detail))
      (is (= :reload-workflow-detail (:type (:side-effect m))))
      (is (= wf-id-1 (:workflow-id (:side-effect m)))))))

;; ---------------------------------------------------------------------------
;; :msg/workflow-detail-loaded merges data into model
;; ---------------------------------------------------------------------------

(deftest workflow-detail-loaded-test
  (testing "merges loaded detail into active view"
    (let [detail {:workflow-id wf-id-1
                  :phases [{:phase :plan :status :success}
                           {:phase :implement :status :running}]
                  :agent-output "output text"
                  :artifacts [{:id "a1" :type :file :name "f.clj" :phase :plan}]}
          m (-> (two-workflows)
                (assoc :view :workflow-detail)
                (assoc-in [:detail :workflow-id] wf-id-1)
                (update/update-model [:msg/workflow-detail-loaded
                                      {:workflow-id wf-id-1 :detail detail}]))]
      (is (= 2 (count (get-in m [:detail :phases]))))
      (is (= "output text" (get-in m [:detail :agent-output])))
      (is (= 1 (count (get-in m [:detail :artifacts]))))))

  (testing "updates workflow row snapshot even when detail view is not active"
    (let [detail {:workflow-id wf-id-1
                  :phases [{:phase :verify :status :success}]}
          m (-> (two-workflows)
                (assoc :view :workflow-list)  ;; NOT in detail view
                (update/update-model [:msg/workflow-detail-loaded
                                      {:workflow-id wf-id-1 :detail detail}]))]
      ;; Row snapshot should be updated for future detail entry
      (is (= 1 (count (get-in m [:workflows 0 :detail-snapshot :phases]))))))

  (testing "ignores detail-loaded for non-matching workflow-id"
    (let [other-id (random-uuid)
          m (-> (two-workflows)
                (assoc :view :workflow-detail)
                (assoc-in [:detail :workflow-id] wf-id-1)
                (assoc-in [:detail :phases] [{:phase :plan :status :running}])
                (update/update-model [:msg/workflow-detail-loaded
                                      {:workflow-id other-id
                                       :detail {:workflow-id other-id
                                                :phases [{:phase :x :status :done}]}}]))]
      ;; Active detail should be unchanged
      (is (= 1 (count (get-in m [:detail :phases]))))
      (is (= :plan (:phase (first (get-in m [:detail :phases]))))))))

;; ---------------------------------------------------------------------------
;; PR detail sibling navigation triggers auto-analysis
;; ---------------------------------------------------------------------------

(deftest pr-detail-right-arrow-triggers-side-effects-test
  (testing "navigating to next PR in detail triggers policy eval + auto-analysis"
    (let [pr-1 {:pr/repo "r1" :pr/number 1 :pr/title "PR One"}
          pr-2 {:pr/repo "r1" :pr/number 2 :pr/title "PR Two"}
          m (-> (util/fresh-model)
                (assoc :view :pr-fleet)
                (assoc :pr-items [pr-1 pr-2])
                (update/update-model [:input :key/enter])    ;; enter pr-1 detail
                ;; Clear side-effects from first enter
                (dissoc :side-effects :side-effect)
                (update/update-model [:input :key/right]))]   ;; → pr-2
      (is (util/view-is? m :pr-detail))
      (is (= "PR Two" (get-in m [:detail :selected-pr :pr/title])))
      ;; Should fire effects for the new PR
      (is (some? (:side-effects m)))
      (is (some #(= :evaluate-policy (:type %)) (:side-effects m))))))
