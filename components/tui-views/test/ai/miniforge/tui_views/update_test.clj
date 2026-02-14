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
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-views.test-util :as util]
   [ai.miniforge.tui-views.update :as update]))

(def wf-id-1 (random-uuid))
(def wf-id-2 (random-uuid))

(defn- two-workflows []
  (util/with-workflows (util/fresh-model)
    [{:workflow-id wf-id-1 :name "wf-1"}
     {:workflow-id wf-id-2 :name "wf-2"}]))

(deftest navigation-test
  (testing "j moves down in workflow list"
    (let [m (util/apply-updates (two-workflows) [[:input :key/j]])]
      (is (util/selected-idx-is? m 1))))

  (testing "k moves up in workflow list"
    (let [m (util/apply-updates (two-workflows)
              [[:input :key/j]
               [:input :key/k]])]
      (is (util/selected-idx-is? m 0))))

  (testing "k at top stays at 0"
    (let [m (util/apply-updates (two-workflows) [[:input :key/k]])]
      (is (util/selected-idx-is? m 0))))

  (testing "j at bottom stays at max"
    (let [m (util/apply-updates (two-workflows)
              [[:input :key/j]
               [:input :key/j]
               [:input :key/j]])]
      (is (util/selected-idx-is? m 1))))

  (testing "g goes to top"
    (let [m (util/apply-updates (two-workflows)
              [[:input :key/j]
               [:input :key/g]])]
      (is (util/selected-idx-is? m 0))))

  (testing "G goes to bottom"
    (let [m (util/apply-updates (two-workflows) [[:input :key/G]])]
      (is (util/selected-idx-is? m 1)))))

(deftest view-navigation-test
  (testing "Enter drills into workflow detail"
    (let [m (util/apply-updates (two-workflows) [[:input :key/enter]])]
      (is (util/view-is? m :workflow-detail))
      (is (= wf-id-1 (get-in m [:detail :workflow-id])))))

  (testing "Escape returns to workflow list"
    (let [m (util/apply-updates (two-workflows)
              [[:input :key/enter]
               [:input :key/escape]])]
      (is (util/view-is? m :workflow-list))))

  (testing "Number keys switch views"
    (is (util/view-is? (update/update-model (util/fresh-model) [:input :key/d1]) :workflow-list))
    (is (util/view-is? (update/update-model (util/fresh-model) [:input :key/d2]) :workflow-detail))
    (is (util/view-is? (update/update-model (util/fresh-model) [:input :key/d3]) :evidence))
    (is (util/view-is? (update/update-model (util/fresh-model) [:input :key/d4]) :artifact-browser))
    (is (util/view-is? (update/update-model (util/fresh-model) [:input :key/d5]) :dag-kanban))))

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

(deftest mode-switching-test
  (testing "Colon enters command mode"
    (let [m (util/apply-updates (util/fresh-model) [[:input :key/colon]])]
      (is (util/mode-is? m :command))
      (is (= ":" (:command-buf m)))))

  (testing "Slash enters search mode"
    (let [m (util/apply-updates (util/fresh-model) [[:input :key/slash]])]
      (is (util/mode-is? m :search))
      (is (= "/" (:command-buf m)))))

  (testing "Escape exits mode"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input :key/colon]
               [:input :key/escape]])]
      (is (util/mode-is? m :normal))
      (is (= "" (:command-buf m))))))

;; ---------------------------------------------------------------------------
;; New tests: keybindings, help overlay, gate results, PR navigation
;; ---------------------------------------------------------------------------

(deftest new-keybinding-test
  (testing "r key triggers refresh with flash message"
    (let [m (update/update-model (util/fresh-model) [:input :key/r])]
      (is (= "Refreshed" (:flash-message m)))
      (is (some? (:last-updated m)))))

  (testing "b key switches to dag-kanban view"
    (let [m (update/update-model (util/fresh-model) [:input :key/b])]
      (is (util/view-is? m :dag-kanban))
      (is (util/selected-idx-is? m 0))))

  (testing "e key switches to evidence view"
    (let [m (update/update-model (util/fresh-model) [:input :key/e])]
      (is (util/view-is? m :evidence))
      (is (util/selected-idx-is? m 0))))

  (testing "a key switches to artifact-browser view"
    (let [m (update/update-model (util/fresh-model) [:input :key/a])]
      (is (util/view-is? m :artifact-browser))
      (is (util/selected-idx-is? m 0))))

  (testing "? key toggles help overlay on"
    (let [m (update/update-model (util/fresh-model) [:input :key/question])]
      (is (true? (:help-visible? m)))))

  (testing "Space key toggles expand on selected node"
    (let [m (update/update-model (util/fresh-model) [:input :key/space])]
      (is (contains? (get-in m [:detail :expanded-nodes]) 0))))

  (testing "Space key toggles expand off when pressed again"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input :key/space]
               [:input :key/space]])]
      (is (not (contains? (get-in m [:detail :expanded-nodes]) 0)))))

  (testing "6 key switches to pr-fleet view"
    (let [m (update/update-model (util/fresh-model) [:input :key/d6])]
      (is (util/view-is? m :pr-fleet))
      (is (util/selected-idx-is? m 0))))

  (testing "7 key switches to pr-detail view"
    (let [m (update/update-model (util/fresh-model) [:input :key/d7])]
      (is (util/view-is? m :pr-detail))
      (is (util/selected-idx-is? m 0))))

  (testing "8 key switches to train-view"
    (let [m (update/update-model (util/fresh-model) [:input :key/d8])]
      (is (util/view-is? m :train-view))
      (is (util/selected-idx-is? m 0)))))

(deftest help-overlay-dismissal-test
  (testing "? toggles help on then off"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input :key/question]
               [:input :key/question]])]
      (is (false? (:help-visible? m)))))

  (testing "Escape dismisses help overlay"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input :key/question]
               [:input :key/escape]])]
      (is (false? (:help-visible? m)))))

  (testing "Navigation keys blocked while help is visible"
    (let [m (util/apply-updates (two-workflows)
              [[:input :key/question]
               [:input :key/j]])]
      (is (util/selected-idx-is? m 0))
      (is (true? (:help-visible? m)))))

  (testing "q still quits even with help visible"
    (let [m (util/apply-updates (util/fresh-model)
              [[:input :key/question]
               [:input :key/q]])]
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
      (is (clojure.string/includes? (:flash-message m) "FAILED"))))

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

  (testing "Enter from train-view drills into pr-detail"
    (let [pr-item {:pr/id 99 :pr/title "Train PR"}
          m (-> (util/fresh-model)
                (assoc :view :train-view)
                (assoc-in [:detail :selected-train :train/prs] [pr-item])
                (update/update-model [:input :key/enter]))]
      (is (util/view-is? m :pr-detail))
      (is (= pr-item (get-in m [:detail :selected-pr]))))))
