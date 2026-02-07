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
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update :as update]))

(def wf-id-1 (random-uuid))
(def wf-id-2 (random-uuid))

(defn- with-workflows [model]
  (-> model
      (update/update-model [:msg/workflow-added {:workflow-id wf-id-1 :name "wf-1"}])
      (update/update-model [:msg/workflow-added {:workflow-id wf-id-2 :name "wf-2"}])))

(deftest navigation-test
  (testing "j moves down in workflow list"
    (let [m (-> (model/init-model) with-workflows
                (update/update-model [:input :key/j]))]
      (is (= 1 (:selected-idx m)))))

  (testing "k moves up in workflow list"
    (let [m (-> (model/init-model) with-workflows
                (update/update-model [:input :key/j])
                (update/update-model [:input :key/k]))]
      (is (= 0 (:selected-idx m)))))

  (testing "k at top stays at 0"
    (let [m (-> (model/init-model) with-workflows
                (update/update-model [:input :key/k]))]
      (is (= 0 (:selected-idx m)))))

  (testing "j at bottom stays at max"
    (let [m (-> (model/init-model) with-workflows
                (update/update-model [:input :key/j])
                (update/update-model [:input :key/j])
                (update/update-model [:input :key/j]))]
      (is (= 1 (:selected-idx m)))))

  (testing "g goes to top"
    (let [m (-> (model/init-model) with-workflows
                (update/update-model [:input :key/j])
                (update/update-model [:input :key/g]))]
      (is (= 0 (:selected-idx m)))))

  (testing "G goes to bottom"
    (let [m (-> (model/init-model) with-workflows
                (update/update-model [:input :key/G]))]
      (is (= 1 (:selected-idx m))))))

(deftest view-navigation-test
  (testing "Enter drills into workflow detail"
    (let [m (-> (model/init-model) with-workflows
                (update/update-model [:input :key/enter]))]
      (is (= :workflow-detail (:view m)))
      (is (= wf-id-1 (get-in m [:detail :workflow-id])))))

  (testing "Escape returns to workflow list"
    (let [m (-> (model/init-model) with-workflows
                (update/update-model [:input :key/enter])
                (update/update-model [:input :key/escape]))]
      (is (= :workflow-list (:view m)))))

  (testing "Number keys switch views"
    (is (= :workflow-list (:view (update/update-model (model/init-model) [:input :key/d1]))))
    (is (= :workflow-detail (:view (update/update-model (model/init-model) [:input :key/d2]))))
    (is (= :evidence (:view (update/update-model (model/init-model) [:input :key/d3]))))
    (is (= :artifact-browser (:view (update/update-model (model/init-model) [:input :key/d4]))))
    (is (= :dag-kanban (:view (update/update-model (model/init-model) [:input :key/d5]))))))

(deftest workflow-events-test
  (testing "Workflow added appears in list"
    (let [m (update/update-model (model/init-model)
              [:msg/workflow-added {:workflow-id wf-id-1 :name "test-wf"}])]
      (is (= 1 (count (:workflows m))))
      (is (= "test-wf" (:name (first (:workflows m)))))))

  (testing "Phase changed updates workflow"
    (let [m (-> (model/init-model)
                (update/update-model [:msg/workflow-added {:workflow-id wf-id-1 :name "test"}])
                (update/update-model [:msg/phase-changed {:workflow-id wf-id-1 :phase :implement}]))]
      (is (= :implement (:phase (first (:workflows m)))))))

  (testing "Workflow done updates status"
    (let [m (-> (model/init-model)
                (update/update-model [:msg/workflow-added {:workflow-id wf-id-1 :name "test"}])
                (update/update-model [:msg/workflow-done {:workflow-id wf-id-1 :status :success}]))]
      (is (= :success (:status (first (:workflows m)))))
      (is (= 100 (:progress (first (:workflows m)))))))

  (testing "Workflow failed updates status"
    (let [m (-> (model/init-model)
                (update/update-model [:msg/workflow-added {:workflow-id wf-id-1 :name "test"}])
                (update/update-model [:msg/workflow-failed {:workflow-id wf-id-1 :error "timeout"}]))]
      (is (= :failed (:status (first (:workflows m))))))))

(deftest mode-switching-test
  (testing ": enters command mode"
    (let [m (update/update-model (model/init-model) [:input :key/colon])]
      (is (= :command (:mode m)))
      (is (= ":" (:command-buf m)))))

  (testing "/ enters search mode"
    (let [m (update/update-model (model/init-model) [:input :key/slash])]
      (is (= :search (:mode m)))
      (is (= "/" (:command-buf m)))))

  (testing "Escape exits command mode"
    (let [m (-> (model/init-model)
                (update/update-model [:input :key/colon])
                (update/update-model [:input :key/escape]))]
      (is (= :normal (:mode m)))))

  (testing "Character input in command mode appends to buffer"
    (let [m (-> (model/init-model)
                (update/update-model [:input :key/colon])
                (update/update-model [:input {:type :char :char \h}])
                (update/update-model [:input {:type :char :char \i}]))]
      (is (= ":hi" (:command-buf m)))))

  (testing "Backspace in command mode removes last char"
    (let [m (-> (model/init-model)
                (update/update-model [:input :key/colon])
                (update/update-model [:input {:type :char :char \x}])
                (update/update-model [:input :key/backspace]))]
      (is (= ":" (:command-buf m))))))

(deftest quit-test
  (testing "q sets quit flag"
    (let [m (update/update-model (model/init-model) [:input :key/q])]
      (is (true? (:quit? m))))))

(deftest agent-output-test
  (testing "Agent output accumulates when viewing detail"
    (let [m (-> (model/init-model)
                (update/update-model [:msg/workflow-added {:workflow-id wf-id-1 :name "test"}])
                (update/update-model [:input :key/enter])
                (update/update-model [:msg/agent-output {:workflow-id wf-id-1 :delta "Hello "}])
                (update/update-model [:msg/agent-output {:workflow-id wf-id-1 :delta "World"}]))]
      (is (= "Hello World" (get-in m [:detail :agent-output]))))))
