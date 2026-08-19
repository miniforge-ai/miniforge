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
(ns ai.miniforge.cli.web.components-test.page-and-status-test
  "Tests for the page chrome and the sidebar workflow-status widget — both
   render without any PR fixture data. Split out of `components_test.clj`
   (rule 210)."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.web.components :as sut]
   [ai.miniforge.cli.web.fleet :as fleet]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} page-uses-localized-title-test
  (testing "the dashboard page title comes from the message catalog"
    (let [html (sut/page [:div "body"])]
      (is (str/includes? html "<title>Miniforge Fleet Dashboard</title>")))))

(deftest ^{:stratum 0} workflow-status-renders-localized-summary-test
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
