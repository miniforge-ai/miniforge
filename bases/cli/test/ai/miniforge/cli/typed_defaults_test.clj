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

(ns ai.miniforge.cli.typed-defaults-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.main.commands.pr-policy-respond :as pr-policy]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]))

(deftest commit-sha-text-is-always-a-string-test
  (testing "valid commit SHAs are preserved"
    (is (= "abc123" (#'pr-policy/commit-sha-text {:commit-sha "abc123"}))))
  (testing "absent and malformed commit SHAs use the display sentinel"
    (doseq [data [{}
                  {:commit-sha nil}
                  {:commit-sha false}
                  {:commit-sha :abc123}
                  {:commit-sha 42}]]
      (is (= "—" (#'pr-policy/commit-sha-text data))))))

(deftest response-output-is-always-a-map-test
  (testing "valid response output is merged into top-level projection fields"
    (is (= {:content "outer" :exit-code 0 :detail :ok}
           (#'workflow-runner/response-output
            {:content "outer" :exit-code 0 :output {:detail :ok}}))))
  (testing "absent and malformed output preserves only top-level projection fields"
    (doseq [output [nil false :not-a-map 42 []]]
      (is (= {:content "outer"}
             (#'workflow-runner/response-output
              {:content "outer" :output output}))))))
