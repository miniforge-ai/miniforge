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

(ns ai.miniforge.tui-views.view-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update :as update]
   [ai.miniforge.tui-views.view :as view]))

(deftest root-view-renders-test
  (testing "Workflow list view renders without error"
    (let [m (model/init-model)
          buf (view/root-view m [80 24])]
      (is (some? buf))
      (is (= 24 (count buf)))
      (is (= 80 (count (first buf))))))

  (testing "Workflow list with data renders"
    (let [m (-> (model/init-model)
                (update/update-model [:msg/workflow-added
                                      {:workflow-id (random-uuid) :name "deploy-v2"}]))
          buf (view/root-view m [80 24])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "MINIFORGE") strings))
      (is (some #(str/includes? % "deploy-v2") strings)))))

(deftest workflow-detail-view-renders-test
  (testing "Detail view renders without error"
    (let [wf-id (random-uuid)
          m (-> (model/init-model)
                (update/update-model [:msg/workflow-added {:workflow-id wf-id :name "test"}])
                (update/update-model [:input :key/enter]))
          buf (view/root-view m [80 24])]
      (is (some? buf))
      (is (= 24 (count buf))))))

(deftest evidence-view-renders-test
  (testing "Evidence view renders without error"
    (let [m (assoc (model/init-model) :view :evidence)
          buf (view/root-view m [80 24])]
      (is (some? buf)))))

(deftest artifact-browser-view-renders-test
  (testing "Artifact browser view renders without error"
    (let [m (assoc (model/init-model) :view :artifact-browser)
          buf (view/root-view m [80 24])]
      (is (some? buf)))))

(deftest dag-kanban-view-renders-test
  (testing "DAG kanban view renders without error"
    (let [m (assoc (model/init-model) :view :dag-kanban)
          buf (view/root-view m [80 24])]
      (is (some? buf)))))

(deftest minimum-terminal-size-test
  (testing "Graceful behavior at minimum size"
    (let [m (model/init-model)
          buf (view/root-view m [80 24])]
      (is (= 24 (count buf)))))

  (testing "Very small terminal shows error"
    (let [m (model/init-model)
          buf (view/root-view m [20 3])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "small") strings)))))

(deftest command-bar-overlay-test
  (testing "Command mode shows command bar"
    (let [m (-> (model/init-model)
                (update/update-model [:input :key/colon]))
          buf (view/root-view m [80 24])
          strings (layout/buf->strings buf)
          last-line (last strings)]
      (is (str/includes? last-line ":")))))
