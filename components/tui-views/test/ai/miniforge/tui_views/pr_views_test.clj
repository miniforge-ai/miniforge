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
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.views.pr-fleet :as pr-fleet]
   [ai.miniforge.tui-views.views.pr-detail :as pr-detail]
   [ai.miniforge.tui-views.views.train-view :as train-view]
   [ai.miniforge.tui-views.model :as model]))

;; --- Mock data ---

(def sample-prs
  [{:pr/repo "my-app" :pr/number 42 :pr/title "Fix auth bug"
    :pr/status :open :pr/readiness-score 0.75
    :pr/risk {:risk/level :low} :pr/policy-passed? true}
   {:pr/repo "api" :pr/number 101 :pr/title "Add caching layer"
    :pr/status :merge-ready :pr/readiness-score 1.0
    :pr/risk {:risk/level :medium} :pr/policy-passed? true}
   {:pr/repo "infra" :pr/number 7 :pr/title "Update DNS records"
    :pr/status :draft :pr/readiness-score 0.2
    :pr/risk {:risk/level :high} :pr/policy-passed? false}])

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

;; --- PR Fleet tests ---

(deftest pr-fleet-empty-test
  (testing "PR fleet renders empty state"
    (let [m (assoc (model/init-model) :view :pr-fleet)
          buf (pr-fleet/render m [80 24])
          strings (layout/buf->strings buf)]
      (is (some? buf))
      (is (= 24 (count buf)))
      (is (some #(str/includes? % "PR Fleet") strings)))))

(deftest pr-fleet-with-data-test
  (testing "PR fleet renders PR rows"
    (let [m (assoc (model/init-model) :view :pr-fleet :pr-items sample-prs)
          buf (pr-fleet/render m [80 24])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "my-app") strings))
      (is (some #(str/includes? % "#42") strings))
      (is (some #(str/includes? % "api") strings)))))

(deftest pr-fleet-dimensions-test
  (testing "PR fleet renders at exact dimensions"
    (let [m (assoc (model/init-model) :view :pr-fleet :pr-items sample-prs)
          buf (pr-fleet/render m [120 30])]
      (is (= 30 (count buf)))
      (is (= 120 (count (first buf)))))))

;; --- PR Detail tests ---

(deftest pr-detail-empty-test
  (testing "PR detail renders with nil data gracefully"
    (let [m (assoc (model/init-model) :view :pr-detail)
          buf (pr-detail/render m [80 24])]
      (is (some? buf))
      (is (= 24 (count buf))))))

(deftest pr-detail-with-data-test
  (testing "PR detail renders readiness and risk"
    (let [m (-> (model/init-model)
                (assoc :view :pr-detail)
                (assoc-in [:detail :selected-pr]
                          {:pr/repo "my-app" :pr/number 42 :pr/title "Fix auth"})
                (assoc-in [:detail :pr-readiness] sample-readiness)
                (assoc-in [:detail :pr-risk] sample-risk))
          buf (pr-detail/render m [80 24])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "my-app") strings))
      (is (some #(str/includes? % "Readiness") strings))
      (is (some #(str/includes? % "Risk") strings)))))

(deftest pr-detail-with-gates-test
  (testing "PR detail renders gate results"
    (let [m (-> (model/init-model)
                (assoc :view :pr-detail)
                (assoc-in [:detail :selected-pr]
                          {:pr/repo "api" :pr/number 101 :pr/title "Add caching"
                           :pr/gate-results [{:gate/id :lint :gate/passed? true}
                                             {:gate/id :security :gate/passed? false}]})
                (assoc-in [:detail :pr-readiness] sample-readiness)
                (assoc-in [:detail :pr-risk] sample-risk))
          buf (pr-detail/render m [80 24])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "Gates") strings)))))

;; --- Train View tests ---

(deftest train-view-empty-test
  (testing "Train view renders empty train"
    (let [m (-> (model/init-model)
                (assoc :view :train-view)
                (assoc-in [:detail :selected-train]
                          {:train/name "empty-train" :train/prs []}))
          buf (train-view/render m [80 24])
          strings (layout/buf->strings buf)]
      (is (some? buf))
      (is (some #(str/includes? % "empty-train") strings))
      (is (some #(str/includes? % "Empty train") strings)))))

(deftest train-view-with-data-test
  (testing "Train view renders PRs in order"
    (let [m (-> (model/init-model)
                (assoc :view :train-view)
                (assoc-in [:detail :selected-train] sample-train))
          buf (train-view/render m [80 24])
          strings (layout/buf->strings buf)]
      (is (some #(str/includes? % "release-v3") strings))
      (is (some #(str/includes? % "2/4") strings))
      (is (some #(str/includes? % "Schema") strings)))))

(deftest train-view-dimensions-test
  (testing "Train view renders at exact dimensions"
    (let [m (-> (model/init-model)
                (assoc :view :train-view)
                (assoc-in [:detail :selected-train] sample-train))
          buf (train-view/render m [100 30])]
      (is (= 30 (count buf)))
      (is (= 100 (count (first buf)))))))
