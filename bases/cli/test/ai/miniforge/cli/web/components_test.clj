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

(ns ai.miniforge.cli.web.components-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.web.components :as sut]
   [ai.miniforge.cli.web.fleet :as fleet]))

(def sample-analysis
  {:risk :low
   :complexity :simple
   :summary "Documentation update"
   :suggested-action "Safe to merge"
   :reasons ["2 files modified"]
   :total-changes 18
   :file-count 2})

(def sample-selected-pr
  {:number 42
   :title "Improve dashboard coverage"
   :author {:login "chris"}
   :url "https://example.test/pr/42"
   :repo "miniforge"
   :additions 12
   :deletions 6
   :analysis sample-analysis})

(def sample-fleet
  [{:repo "miniforge"
    :prs [sample-selected-pr]}])

(deftest page-uses-localized-title-test
  (testing "the dashboard page title comes from the message catalog"
    (let [html (sut/page [:div "body"])]
      (is (str/includes? html "<title>Miniforge Fleet Dashboard</title>")))))

(deftest pr-detail-renders-localized-controls-test
  (testing "PR detail renders localized section and action labels"
    (let [html (str (sut/pr-detail sample-selected-pr))]
      (is (str/includes? html "AI Analysis"))
      (is (str/includes? html "Risk Level"))
      (is (str/includes? html "Open in GitHub"))
      (is (str/includes? html "What could break?")))))

(deftest dashboard-renders-summary-and-shortcuts-test
  (testing "dashboard renders localized counts, actions, and keyboard hints"
    (with-redefs [fleet/generate-summary (constantly {:total 1
                                                      :recommendation "Review the safe PR."
                                                      :high-risk {:count 0}
                                                      :medium-risk {:count 0}
                                                      :low-risk {:count 1}})
                  fleet/get-workflow-status (constantly {:running 0
                                                         :failed 0
                                                         :succeeded 1
                                                         :runs []})]
      (let [html (str (sut/dashboard sample-fleet sample-selected-pr {:overall :healthy}))]
        (is (str/includes? html "Fleet Dashboard"))
        (is (str/includes? html "Batch Approve Safe"))
        (is (str/includes? html "Approve all 1 low-risk PRs?"))
        (is (str/includes? html "j</kbd>/"))
        (is (str/includes? html "refresh"))))))

(deftest workflow-status-renders-localized-summary-test
  (testing "workflow status renders counts and the empty-state copy"
    (with-redefs [fleet/get-workflow-status (constantly {:running 1
                                                         :failed 2
                                                         :succeeded 3
                                                         :runs []})]
      (let [html (str (sut/workflow-status ["miniforge"]))]
        (is (str/includes? html "Workflow Status"))
        (is (str/includes? html "1 ⏳"))
        (is (str/includes? html "2 ✗"))
        (is (str/includes? html "3 ✓"))
        (is (str/includes? html "No recent workflows"))))))

(deftest empty-detail-renders-localized-empty-state-test
  (testing "empty detail renders the localized empty state copy"
    (let [html (str (sut/empty-detail))]
      (is (str/includes? html "Select a PR to view details"))
      (is (str/includes? html "Choose a pull request from the list")))))
