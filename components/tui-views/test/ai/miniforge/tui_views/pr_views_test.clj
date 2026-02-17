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

(ns ai.miniforge.tui-views.pr-views-test
  "Tests for PR-related views via the declarative view-spec interpreter.

   Uses the interpreter path (screens.edn + interpret.clj) which is what
   the TUI actually renders at runtime."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [ai.miniforge.tui-engine.interface :as engine]
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.view.interpret :as interpret]
   [ai.miniforge.tui-views.view.project :as project]
   [ai.miniforge.tui-views.update.navigation :as nav]
   [ai.miniforge.tui-views.model :as model]))

;; --- Mock data ---

(def sample-prs
  [{:pr/repo "my-app" :pr/number 42 :pr/title "Fix auth bug"
    :pr/status :open :pr/ci-status :pending :pr/url "https://github.com/my-app/pull/42"
    :pr/policy-passed? true}
   {:pr/repo "api" :pr/number 101 :pr/title "Add caching layer"
    :pr/status :merge-ready :pr/ci-status :passed :pr/url "https://github.com/api/pull/101"
    :pr/policy-passed? true}
   {:pr/repo "infra" :pr/number 7 :pr/title "Update DNS records"
    :pr/status :draft :pr/ci-status :pending :pr/url "https://github.com/infra/pull/7"
    :pr/policy-passed? false}])

(def sample-readiness
  {:readiness/score 0.8
   :readiness/factors [{:factor :ci-green :weight 0.4 :score 1.0}
                       {:factor :review-approved :weight 0.3 :score 0.8}
                       {:factor :no-conflicts :weight 0.3 :score 0.6}]})

(def sample-risk
  {:risk/level :low
   :risk/factors [{:factor :file-churn :explanation "Low file churn"}
                  {:factor :author-familiarity :explanation "Author familiar with area"}]})

(def sample-train
  {:train/name "release-v3"
   :train/status :merging
   :train/progress {:merged 2 :total 4}
   :train/prs [{:pr/merge-order 1 :pr/repo "api" :pr/number 10
                :pr/title "Schema migration" :pr/status :merged :pr/ci-status :green}
               {:pr/merge-order 2 :pr/repo "web" :pr/number 20
                :pr/title "UI updates" :pr/status :merging :pr/ci-status :green}
               {:pr/merge-order 3 :pr/repo "api" :pr/number 30
                :pr/title "Add endpoint" :pr/status :queued :pr/ci-status :pending}
               {:pr/merge-order 4 :pr/repo "infra" :pr/number 5
                :pr/title "Terraform update" :pr/status :blocked :pr/ci-status :failed
                :pr/depends-on ["pr-30"] :pr/blocks []}]})

;; --- Helper ---

(defn- render-view
  "Render a view through the interpreter (the production path)."
  [model size]
  (let [theme (engine/get-theme (:theme model))
        spec (interpret/get-screen-spec (:view model))]
    (interpret/render-screen model theme size spec)))

;; --- PR Fleet tests ---

(deftest pr-fleet-empty-test
  (testing "PR fleet renders empty state via interpreter"
    (let [m (assoc (model/init-model) :view :pr-fleet)
          buf (render-view m [80 24])
          strings (layout/buf->strings buf)]
      (is (some? buf))
      (is (= 24 (count buf)))
      (is (some #(str/includes? % "PR Fleet") strings)))))

(deftest pr-fleet-with-data-test
  (testing "PR fleet renders PR rows via interpreter"
    (let [m (assoc (model/init-model) :view :pr-fleet :pr-items sample-prs)
          buf (render-view m [80 24])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "my-app") strings))
      (is (some #(str/includes? % "Fix auth") strings))
      (is (some #(str/includes? % "api") strings)))))

(deftest pr-fleet-dimensions-test
  (testing "PR fleet renders at exact dimensions"
    (let [m (assoc (model/init-model) :view :pr-fleet :pr-items sample-prs)
          buf (render-view m [120 30])]
      (is (= 30 (count buf)))
      (is (= 120 (count (first buf)))))))

;; --- PR Detail tests ---

(deftest pr-detail-empty-test
  (testing "PR detail renders with nil data gracefully"
    (let [m (assoc (model/init-model) :view :pr-detail)
          buf (render-view m [80 24])]
      (is (some? buf))
      (is (= 24 (count buf))))))

(deftest pr-detail-with-data-test
  (testing "PR detail renders readiness and risk via interpreter"
    (let [m (-> (model/init-model)
                (assoc :view :pr-detail)
                (assoc-in [:detail :selected-pr]
                          {:pr/repo "my-app" :pr/number 42 :pr/title "Fix auth"})
                (assoc-in [:detail :pr-readiness] sample-readiness)
                (assoc-in [:detail :pr-risk] sample-risk))
          buf (render-view m [80 24])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "my-app") strings))
      (is (some #(str/includes? % "Readiness") strings))
      (is (some #(str/includes? % "Risk") strings)))))

(deftest pr-detail-with-gates-test
  (testing "PR detail renders gate results via interpreter"
    (let [m (-> (model/init-model)
                (assoc :view :pr-detail)
                (assoc-in [:detail :selected-pr]
                          {:pr/repo "api" :pr/number 101 :pr/title "Add caching"
                           :pr/gate-results [{:gate/id :lint :gate/passed? true}
                                             {:gate/id :security :gate/passed? false}]})
                (assoc-in [:detail :pr-readiness] sample-readiness)
                (assoc-in [:detail :pr-risk] sample-risk))
          buf (render-view m [80 24])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "Gates") strings)))))

;; --- Train View tests ---

(deftest train-view-empty-test
  (testing "Train view renders empty train via interpreter"
    (let [m (-> (model/init-model)
                (assoc :view :train-view)
                (assoc-in [:detail :selected-train]
                          {:train/name "empty-train" :train/prs []}))
          buf (render-view m [80 24])
          strings (layout/buf->strings buf)]
      (is (some? buf))
      (is (some #(str/includes? % "empty-train") strings)))))

(deftest train-view-with-data-test
  (testing "Train view renders PRs in order via interpreter"
    (let [m (-> (model/init-model)
                (assoc :view :train-view)
                (assoc-in [:detail :selected-train] sample-train))
          buf (render-view m [80 24])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "release-v3") strings))
      (is (some #(str/includes? % "Schema") strings)))))

(deftest train-view-dimensions-test
  (testing "Train view renders at exact dimensions"
    (let [m (-> (model/init-model)
                (assoc :view :train-view)
                (assoc-in [:detail :selected-train] sample-train))
          buf (render-view m [100 30])]
      (is (= 30 (count buf)))
      (is (= 100 (count (first buf)))))))

;; --- Readiness derivation tests ---

(deftest derive-readiness-merge-ready-test
  (testing "Merge-ready + passed CI = merge-ready 1.0"
    (let [r (project/derive-readiness {:pr/status :merge-ready :pr/ci-status :passed})]
      (is (= :merge-ready (:readiness/state r)))
      (is (= 1.0 (:readiness/score r)))
      (is (empty? (:readiness/blockers r))))))

(deftest derive-readiness-ci-failing-test
  (testing "Open + failed CI = ci-failing with blocker"
    (let [r (project/derive-readiness {:pr/status :open :pr/ci-status :failed})]
      (is (= :ci-failing (:readiness/state r)))
      (is (some #(= :ci (:blocker/type %)) (:readiness/blockers r))))))

(deftest derive-readiness-needs-review-test
  (testing "Open + pending CI = needs-review with blocker"
    (let [r (project/derive-readiness {:pr/status :open :pr/ci-status :pending})]
      (is (= :needs-review (:readiness/state r)))
      (is (some #(= :review (:blocker/type %)) (:readiness/blockers r))))))

(deftest derive-readiness-draft-test
  (testing "Draft = draft with draft blocker"
    (let [r (project/derive-readiness {:pr/status :draft :pr/ci-status :pending})]
      (is (= :draft (:readiness/state r)))
      (is (= 0.1 (:readiness/score r)))
      (is (some #(str/includes? (:blocker/message %) "draft") (:readiness/blockers r))))))

(deftest derive-readiness-changes-requested-test
  (testing "Changes-requested = changes-requested with review blocker"
    (let [r (project/derive-readiness {:pr/status :changes-requested :pr/ci-status :passed})]
      (is (= :changes-requested (:readiness/state r)))
      (is (some #(str/includes? (:blocker/message %) "changes") (:readiness/blockers r))))))

(deftest derive-readiness-has-factors-test
  (testing "Readiness always includes CI, review, and policy factors"
    (let [r (project/derive-readiness {:pr/status :open :pr/ci-status :pending})]
      (is (= 3 (count (:readiness/factors r))))
      (is (= #{:ci :review :policy}
             (set (map :factor (:readiness/factors r))))))))

;; --- Risk derivation tests ---

(deftest derive-risk-low-test
  (testing "Merge-ready = low risk"
    (let [r (project/derive-risk {:pr/status :merge-ready :pr/ci-status :passed})]
      (is (= :low (:risk/level r)))
      (is (seq (:risk/factors r))))))

(deftest derive-risk-ci-failing-test
  (testing "CI failing = medium risk"
    (let [r (project/derive-risk {:pr/status :open :pr/ci-status :failed})]
      (is (= :medium (:risk/level r)))
      (is (some #(str/includes? (:explanation %) "CI") (:risk/factors r))))))

;; --- Enter-detail populates readiness/risk ---

(deftest enter-detail-populates-readiness-test
  (testing "Entering PR detail populates readiness and risk"
    (let [m (-> (model/init-model)
                (assoc :view :pr-fleet
                       :pr-items sample-prs
                       :selected-idx 1))
          m' (nav/enter-detail m)]
      (is (= :pr-detail (:view m')))
      (is (= 101 (get-in m' [:detail :selected-pr :pr/number])))
      (is (= :merge-ready (get-in m' [:detail :pr-readiness :readiness/state])))
      (is (= :low (get-in m' [:detail :pr-risk :risk/level]))))))
