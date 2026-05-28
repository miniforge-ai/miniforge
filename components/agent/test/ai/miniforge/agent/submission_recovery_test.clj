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

(ns ai.miniforge.agent.submission-recovery-test
  "Tests for the agent-agnostic submission-recovery trigger predicate."
  (:require
   [ai.miniforge.agent.submission-recovery :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest fires-on-success-without-artifact
  (testing "a clean success that produced prose but NO artifact triggers recovery
            (the intermittent failure — applies to every agent, not just the planner)"
    (is (true? (boolean (sut/submission-retry? {:success true} nil nil "## Plan\nTask A -> B"))))))

(deftest not-on-success-without-prose
  (testing "a success with no prose has nothing to recover"
    (is (false? (boolean (sut/submission-retry? {:success true} nil nil ""))))
    (is (false? (boolean (sut/submission-retry? {:success true} nil nil nil))))))

(deftest fires-on-recoverable-error-with-stdout
  (testing "adaptive_timeout / cli_error with useful stdout still retries"
    (is (true? (boolean (sut/submission-retry?
                         {:success false :error {:stdout "work" :type "cli_error"}} nil nil ""))))
    (is (true? (boolean (sut/submission-retry?
                         {:success false :error {:stdout "work" :type "adaptive_timeout"}} nil nil ""))))))

(deftest no-fire-when-artifact-present
  (testing "an artifact (submitted or parsed) means no recovery is needed"
    (is (false? (boolean (sut/submission-retry? {:success true} {:code/files []} nil "prose"))))
    (is (false? (boolean (sut/submission-retry? {:success true} nil {:code/files []} "prose"))))))

(deftest no-fire-on-unrecoverable-error
  (testing "an error with no stdout or a non-retriable type does not retry"
    (is (false? (boolean (sut/submission-retry?
                          {:success false :error {:stdout "" :type "cli_error"}} nil nil ""))))
    (is (false? (boolean (sut/submission-retry?
                          {:success false :error {:stdout "x" :type "other"}} nil nil ""))))))
