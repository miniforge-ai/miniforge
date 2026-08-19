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
(ns ai.miniforge.cli.web.components-test.dashboard-test
  "Tests for the top-level `dashboard` orchestrator — summary counts,
   batch-approve action, and keyboard-hint copy. Split out of
   `components_test.clj` (rule 210)."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.web.components :as sut]
   [ai.miniforge.cli.web.components-test.fixtures :as fixtures]
   [ai.miniforge.cli.web.fleet :as fleet]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} dashboard-renders-summary-and-shortcuts-test
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
      (let [html (str (sut/dashboard fixtures/sample-fleet fixtures/sample-selected-pr {:overall :healthy}))]
        (is (str/includes? html "Fleet Dashboard"))
        (is (str/includes? html "Batch Approve Safe"))
        (is (str/includes? html "Approve all 1 low-risk PRs?"))
        (is (str/includes? html "j</kbd>/"))
        (is (str/includes? html "refresh"))))))
