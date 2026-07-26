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
(ns ai.miniforge.bb-test-runner.diagnostic-plan-test
  "Unit tests for `diagnostic-plan`."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.bb-test-runner.diagnostic-plan :as sut]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-parse-diagnostic-args-reads-supported-options
  (testing "diagnostic CLI args parse into a stable-derived plan request"
    (is (= {:mode :expand
            :projects ["miniforge" "miniforge-core"]
            :start-size 2
            :direction :back
            :order :random
            :seed 17}
           (sut/parse-diagnostic-args
            ["mode:expand"
             "project:miniforge:miniforge-core"
             "start-size:2"
             "direction:back"
             "order:random"
             "seed:17"])))))

(deftest ^{:stratum 0} test-parse-diagnostic-args-rejects-invalid-numeric-values
  (testing "numeric diagnostic args return contextual error data"
    (let [result (sut/parse-diagnostic-args ["start-size:abc"])]
      (is (false? (:ok? result)))
      (is (= :bb-test-runner/invalid-diagnostic-arg
             (get-in result [:error :code]))))
    (let [result (sut/parse-diagnostic-args ["seed:not-a-number"])]
      (is (false? (:ok? result)))
      (is (= :bb-test-runner/invalid-diagnostic-arg
             (get-in result [:error :code]))))))

(deftest ^{:stratum 0} test-diagnostic-test-plan-builds-expand-steps
  (testing "diagnostic plans render executable Poly test steps"
    (is (= {:mode :expand
            :summary "Running expand diagnostics across 3 stable-derived projects."
            :projects ["a" "b" "c"]
            :steps [{:label "expand project subset 1/3 (1 projects)"
                     :argv ["clojure" "-M:poly" "test" "project:a"]}
                    {:label "expand project subset 2/3 (2 projects)"
                     :argv ["clojure" "-M:poly" "test" "project:a:b"]}
                    {:label "expand project subset 3/3 (3 projects)"
                     :argv ["clojure" "-M:poly" "test" "project:a:b:c"]}]}
           (sut/diagnostic-test-plan {:mode :expand
                                      :projects ["a" "b" "c"]
                                      :start-size 1}))))
  (testing "missing mode defaults to subset"
    (is (= {:mode :subset
            :summary "Running subset diagnostics across 2 stable-derived projects."
            :projects ["a" "b"]
            :steps [{:label "subset project subset 1/1 (2 projects)"
                     :argv ["clojure" "-M:poly" "test" "project:a:b"]}]}
           (sut/diagnostic-test-plan {:projects ["a" "b"]}))))
  (testing "empty project sets yield an empty step plan"
    (is (= {:mode :expand
            :summary "Running expand diagnostics across 0 stable-derived projects."
            :projects []
            :steps []}
           (sut/diagnostic-test-plan {:mode :expand
                                      :projects []
                                      :start-size 1})))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-test-runner.diagnostic-plan-test)

  :leave-this-here)
