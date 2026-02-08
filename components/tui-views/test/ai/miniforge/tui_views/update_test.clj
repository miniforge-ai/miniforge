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
