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
(ns ai.miniforge.cli.web.components-test.detail-panel-test
  "Tests for the two detail-panel states: no PR selected (`empty-detail`)
   and a PR selected (`pr-detail`). Split out of `components_test.clj`
   (rule 210)."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.web.components :as sut]
   [ai.miniforge.cli.web.components-test.fixtures :as fixtures]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} empty-detail-renders-localized-empty-state-test
  (testing "empty detail renders the localized empty state copy"
    (let [html (str (sut/empty-detail))]
      (is (str/includes? html "Select a PR to view details"))
      (is (str/includes? html "Choose a pull request from the list")))))

(deftest ^{:stratum 0} pr-detail-renders-localized-controls-test
  (testing "PR detail renders localized section and action labels"
    (let [html (str (sut/pr-detail fixtures/sample-selected-pr))]
      (is (str/includes? html "AI Analysis"))
      (is (str/includes? html "Risk Level"))
      (is (str/includes? html "Open in GitHub"))
      (is (str/includes? html "What could break?")))))
