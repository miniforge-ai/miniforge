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
(ns ai.miniforge.workflow.dag-rate-limit-test
  "Tests for handle-rate-limit-in-batch's paused-result artifact rollup."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.workflow.dag-rate-limit :as dag-rate-limit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} no-failover-context
  "Forces handle-rate-limited-batch straight to pause-with-reason: no
   reset time in the batch's messages, and backend failover disabled."
  {:execution/opts {:self-healing {:backend-auto-switch false}}})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} handle-rate-limit-in-batch-pause-includes-same-batch-artifacts-test
  (testing "when a batch has both a task that completed and a task that hit
            a rate limit, pausing must still surface the completed task's
            artifacts — `all-results` only holds PRIOR batches, so the
            current batch has to be folded in explicitly on the pause path"
    (let [batch-results {:task-a (dag/ok {:artifacts ["artifact-a"]})
                         :task-b (dag/err :rate-limited "429 too many requests")}
          decision (dag-rate-limit/handle-rate-limit-in-batch
                    no-failover-context #{:task-b} #{:task-a} #{}
                    {} batch-results nil nil nil)]
      (is (= :pause (:action decision)))
      (is (= ["artifact-a"] (get-in decision [:result :artifacts]))
          "the same-batch completed task's artifact must not be dropped"))))

(deftest ^{:stratum 1} handle-rate-limit-in-batch-pause-still-includes-prior-batch-artifacts-test
  (testing "prior-batch artifacts already in all-results survive alongside
            the newly-folded-in current batch"
    (let [all-results {:task-z (dag/ok {:artifacts ["artifact-z"]})}
          batch-results {:task-a (dag/ok {:artifacts ["artifact-a"]})
                         :task-b (dag/err :rate-limited "429 too many requests")}
          decision (dag-rate-limit/handle-rate-limit-in-batch
                    no-failover-context #{:task-b} #{:task-z :task-a} #{}
                    all-results batch-results nil nil nil)]
      (is (= :pause (:action decision)))
      (is (= #{"artifact-z" "artifact-a"}
             (set (get-in decision [:result :artifacts])))))))
