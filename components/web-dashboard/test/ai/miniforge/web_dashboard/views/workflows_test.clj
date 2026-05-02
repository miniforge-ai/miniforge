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

(ns ai.miniforge.web-dashboard.views.workflows-test
  (:require
   [ai.miniforge.web-dashboard.views.workflows :as sut]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- render-str
  [form]
  (str form))

(deftest workflow-detail-panel-renders-dependency-issues
  (testing "workflow panel shows dependency attribution and dependency section"
    (let [workflow {:id "wf-1"
                    :status :failed
                    :phase :plan
                    :progress 35
                    :dependency-issues [{:dependency/id :anthropic
                                         :dependency/vendor :anthropic
                                         :dependency/kind :provider
                                         :dependency/status :unavailable
                                         :dependency/class :outage
                                         :dependency/retryability :retryable
                                         :dependency/message "Dependency anthropic unavailable"}]
                    :failure-attribution {:dependency/id :anthropic
                                          :dependency/vendor :anthropic
                                          :dependency/kind :provider
                                          :dependency/status :unavailable}}
          result (render-str (sut/workflow-detail-panel workflow []))]
      (is (str/includes? result "Dependency Health"))
      (is (str/includes? result "Anthropic"))
      (is (str/includes? result "Unavailable"))
      (is (str/includes? result "Provider"))
      (is (str/includes? result "Dependency anthropic unavailable")))))

(deftest workflow-list-fragment-renders-dependency-issue-count
  (testing "workflow cards show dependency issue count badge"
    (let [workflows [{:id "wf-2"
                      :name "Dependency Count"
                      :status :running
                      :phase :implement
                      :progress 50
                      :started-at nil
                      :dependency-severity :warning
                      :dependency-issues [{:dependency/id :anthropic
                                           :dependency/status :degraded}]}]
          result (render-str (sut/workflow-list-fragment workflows))]
      (is (str/includes? result "1 dependency issue(s)")))))
