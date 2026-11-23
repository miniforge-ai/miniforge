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
(ns ai.miniforge.cli.workflow-runner.display-cost-test
  "The summary's cost reads as unavailable, or as a lower bound, when some
   spend had no known price; never as a measured $0.0000."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.display-summary-lines :as lines]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} metrics-line [metrics]
  (first (lines/metrics-lines metrics)))

(deftest ^{:stratum 0} workflow-summary-uses-the-same-cost-text-test
  (testing "print-workflow-summary does not print $0.0000 for unpriced spend"
    (let [out (with-out-str
                (display/print-workflow-summary
                 {:execution/status :completed
                  :execution/metrics {:tokens 24885 :cost-usd 0.0 :duration-ms 61000
                                      :cost-unavailable? true}}))]
      (is (str/includes? out "Cost: unavailable"))
      (is (not (str/includes? out "$0.0000"))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unpriced-run-shows-cost-unavailable-test
  (testing "no priced spend at all"
    (let [line (metrics-line {:tokens 24885 :cost-usd 0.0 :duration-ms 61000
                              :cost-unavailable? true})]
      (is (str/includes? line "Tokens: 24885"))
      (is (str/includes? line "Cost: unavailable"))
      (is (not (str/includes? line "$0.0000")))))
  (testing "part of the run priced"
    (is (str/includes? (metrics-line {:tokens 3000 :cost-usd 0.0123 :duration-ms 1000
                                      :cost-unavailable? true})
                       "Cost: at least $0.0123")))
  (testing "a fully priced run keeps the measured cost"
    (is (str/includes? (metrics-line {:tokens 3000 :cost-usd 0.0123 :duration-ms 1000})
                       "Cost: $0.0123"))))
