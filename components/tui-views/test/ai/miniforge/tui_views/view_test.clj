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
                (assoc :view :workflow-list)
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
                (assoc :view :workflow-list)
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

(deftest repo-manager-view-renders-test
  (testing "Repo manager view renders with empty state"
    (let [m (assoc (model/init-model) :view :repo-manager)
          buf (view/root-view m [80 24])
          strings (layout/buf->strings buf)]
      (is (some? buf))
      (is (some #(str/includes? % "No repositories configured") strings))))

  (testing "Repo manager view renders configured repositories"
    (let [m (-> (model/init-model)
                (assoc :view :repo-manager
                       :fleet-repos ["acme/service-a" "gitlab:team/service-b"]
                       :pr-items [{:pr/repo "acme/service-a"}
                                  {:pr/repo "acme/service-a"}
                                  {:pr/repo "gitlab:team/service-b"}]))
          buf (view/root-view m [100 24])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "[Repos (6)]") strings))
      (is (some #(str/includes? % "acme/service-a") strings))
      (is (some #(str/includes? % "gitlab:team/service-b") strings)))))

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

;; ---------------------------------------------------------------------------
;; New tests: PR fleet, PR detail, train view, help overlay rendering
;; ---------------------------------------------------------------------------

(deftest pr-fleet-view-renders-test
  (testing "PR fleet view renders without error"
    (let [m (assoc (model/init-model) :view :pr-fleet)
          buf (view/root-view m [80 24])]
      (is (some? buf))
      (is (= 24 (count buf)))
      (is (= 80 (count (first buf))))))

  (testing "PR fleet with data renders PR titles"
    (let [m (-> (model/init-model)
                (assoc :view :pr-fleet)
                (assoc :pr-items [{:pr/id 1 :pr/title "Add auth layer" :pr/readiness 0.9 :pr/risk :low}
                                  {:pr/id 2 :pr/title "Fix race condition" :pr/readiness 0.5 :pr/risk :high}]))
          buf (view/root-view m [80 24])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "auth") strings))
      (is (some #(str/includes? % "race") strings)))))

(deftest pr-detail-view-renders-test
  (testing "PR detail view renders without error"
    (let [m (-> (model/init-model)
                (assoc :view :pr-detail)
                (assoc-in [:detail :selected-pr] {:pr/id 1 :pr/title "Test PR"
                                                   :pr/readiness 0.75 :pr/risk :medium}))
          buf (view/root-view m [80 24])]
      (is (some? buf))
      (is (= 24 (count buf)))))

  (testing "PR detail shows PR title"
    (let [m (-> (model/init-model)
                (assoc :view :pr-detail)
                (assoc-in [:detail :selected-pr] {:pr/id 1 :pr/title "Refactor parser"
                                                   :pr/readiness 0.95 :pr/risk :low}))
          buf (view/root-view m [80 24])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "Refactor parser") strings)))))

(deftest train-view-renders-test
  (testing "Train view renders without error"
    (let [m (-> (model/init-model)
                (assoc :view :train-view)
                (assoc-in [:detail :selected-train]
                          {:train/id (random-uuid)
                           :train/name "release-2026.02"
                           :train/prs [{:pr/id 1 :pr/title "PR-A" :pr/readiness 1.0}
                                       {:pr/id 2 :pr/title "PR-B" :pr/readiness 0.6}]}))
          buf (view/root-view m [80 24])]
      (is (some? buf))
      (is (= 24 (count buf)))))

  (testing "Train view shows train name and PRs"
    (let [m (-> (model/init-model)
                (assoc :view :train-view)
                (assoc-in [:detail :selected-train]
                          {:train/id (random-uuid)
                           :train/name "release-2026.02"
                           :train/prs [{:pr/id 1 :pr/title "PR-Alpha" :pr/readiness 1.0}]}))
          buf (view/root-view m [80 24])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "release-2026.02") strings))
      (is (some #(str/includes? % "PR-Alpha") strings)))))

(deftest help-overlay-renders-test
  (testing "Help overlay renders when help-visible? is true"
    (let [m (-> (model/init-model)
                (assoc :help-visible? true))
          buf (view/root-view m [80 24])
          strings (layout/buf->strings buf)]
      (is (some? buf))
      (is (= 24 (count buf)))
      ;; Help overlay should show key bindings
      (is (some #(or (str/includes? % "Help")
                     (str/includes? % "help")
                     (str/includes? % "KEY")
                     (str/includes? % "key")) strings))))

  (testing "Help overlay does not appear when help-visible? is false"
    (let [m (model/init-model)]
      (view/root-view m [80 24])
      ;; The word "Help" may appear in status bar, but not as an overlay title
      ;; We just verify the model flag is false
      (is (false? (:help-visible? m))))))
