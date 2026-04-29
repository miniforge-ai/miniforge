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

;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.failure-classifier.classifier-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.failure-classifier.interface :as fc]))

;; ---------------------------------------------------------------------------- Taxonomy tests

(deftest valid-failure-class-test
  (testing "all canonical classes are valid"
    (doseq [cls fc/failure-classes]
      (is (fc/valid-failure-class? cls) (str cls " should be valid"))))

  (testing "bogus classes are invalid"
    (is (not (fc/valid-failure-class? :failure.class/bogus)))
    (is (not (fc/valid-failure-class? :something-else)))
    (is (not (fc/valid-failure-class? nil)))))

(deftest unknown-class-test
  (is (fc/unknown-class? :failure.class/unknown))
  (is (not (fc/unknown-class? :failure.class/timeout))))

;; ---------------------------------------------------------------------------- Classification by anomaly category

(deftest classify-by-anomaly-category-test
  (testing "anomaly categories map to correct failure classes"
    (is (= :failure.class/timeout
           (fc/classify {:anomaly/category :anomalies/timeout})))
    (is (= :failure.class/external
           (fc/classify {:anomaly/category :anomalies/unavailable})))
    (is (= :failure.class/external
           (fc/classify {:anomaly/category :anomalies/interrupted})))
    (is (= :failure.class/resource
           (fc/classify {:anomaly/category :anomalies/busy})))
    (is (= :failure.class/policy
           (fc/classify {:anomaly/category :anomalies/forbidden})))
    (is (= :failure.class/concurrency
           (fc/classify {:anomaly/category :anomalies/conflict})))
    (is (= :failure.class/data-integrity
           (fc/classify {:anomaly/category :anomalies/not-found}))))

  (testing "phase-specific anomalies"
    (is (= :failure.class/resource
           (fc/classify {:anomaly/category :anomalies.phase/budget-exceeded})))
    (is (= :failure.class/agent-error
           (fc/classify {:anomaly/category :anomalies.phase/agent-failed})))
    (is (= :failure.class/policy
           (fc/classify {:anomaly/category :anomalies.gate/check-failed})))
    (is (= :failure.class/external
           (fc/classify {:anomaly/category :anomalies.agent/llm-error})))
    (is (= :failure.class/agent-error
           (fc/classify {:anomaly/category :anomalies.agent/parse-failed})))))

;; ---------------------------------------------------------------------------- Classification by error message

(deftest classify-timeout-messages-test
  (testing "timeout-related messages"
    (is (= :failure.class/timeout
           (fc/classify {:error/message "Connection timed out after 30s"})))
    (is (= :failure.class/timeout
           (fc/classify {:error/message "Deadline exceeded for operation"})))
    (is (= :failure.class/timeout
           (fc/classify {:error/message "TTL expired for capability grant"})))))

(deftest classify-resource-messages-test
  (testing "resource/budget messages"
    (is (= :failure.class/resource
           (fc/classify {:error/message "Budget exhausted: 100000 tokens used"})))
    (is (= :failure.class/resource
           (fc/classify {:error/message "Token limit reached"})))
    (is (= :failure.class/resource
           (fc/classify {:error/message "Max retries exceeded for inner loop"})))
    (is (= :failure.class/resource
           (fc/classify {:error/message "Rate limit exceeded, retry after 60s"})))
    (is (= :failure.class/resource
           (fc/classify {:error/message "Out of memory"})))))

(deftest classify-external-messages-test
  (testing "external service messages"
    (is (= :failure.class/external
           (fc/classify {:error/message "Connection refused to api.anthropic.com"})))
    (is (= :failure.class/external
           (fc/classify {:error/message "DNS resolution failed for host"})))
    (is (= :failure.class/external
           (fc/classify {:error/message "503 Service Unavailable"})))
    (is (= :failure.class/external
           (fc/classify {:error/message "ECONNREFUSED on port 443"})))))

(deftest classify-concurrency-messages-test
  (testing "concurrency/conflict messages"
    (is (= :failure.class/concurrency
           (fc/classify {:error/message "Merge conflict in src/main.clj"})))
    (is (= :failure.class/concurrency
           (fc/classify {:error/message "Lock contention on resource"})))
    (is (= :failure.class/concurrency
           (fc/classify {:error/message "Deadlock detected"})))))

(deftest classify-data-integrity-messages-test
  (testing "data integrity messages"
    (is (= :failure.class/data-integrity
           (fc/classify {:error/message "Hash mismatch: expected abc got def"})))
    (is (= :failure.class/data-integrity
           (fc/classify {:error/message "Schema violation in response"})))
    (is (= :failure.class/data-integrity
           (fc/classify {:error/message "Stale context detected"})))))

(deftest classify-policy-messages-test
  (testing "policy/gate messages"
    (is (= :failure.class/policy
           (fc/classify {:error/message "Gate lint-check failed with 3 violations"})))
    (is (= :failure.class/policy
           (fc/classify {:error/message "Policy rejected: no-resource-creation violation"})))
    (is (= :failure.class/policy
           (fc/classify {:error/message "Capability denied for tool execution"})))))

(deftest classify-tool-error-messages-test
  (testing "tool error messages"
    (is (= :failure.class/tool-error
           (fc/classify {:error/message "Tool error: clj-kondo returned errors"})))
    (is (= :failure.class/tool-error
           (fc/classify {:error/message "Command failed with exit code 1"})))
    (is (= :failure.class/tool-error
           (fc/classify {:error/message "Non-zero exit from git push"})))))

(deftest classify-task-code-messages-test
  (testing "task/code messages"
    (is (= :failure.class/task-code
           (fc/classify {:error/message "Test failure in namespace x"})))
    (is (= :failure.class/task-code
           (fc/classify {:error/message "Compile error in module"})))
    (is (= :failure.class/task-code
           (fc/classify {:error/message "Lint error: unused binding"})))
    (is (= :failure.class/task-code
           (fc/classify {:error/message "Coverage below threshold: 65%"})))))

(deftest classify-agent-error-messages-test
  (testing "agent error messages"
    (is (= :failure.class/agent-error
           (fc/classify {:error/message "Agent hallucinated a non-existent API"})))
    (is (= :failure.class/agent-error
           (fc/classify {:error/message "Failed to parse response as JSON"})))
    (is (= :failure.class/agent-error
           (fc/classify {:error/message "Agent loop failed after 5 iterations"})))))

;; ---------------------------------------------------------------------------- Classification by exception class

(deftest classify-exception-test
  (testing "Java exceptions classified correctly"
    (is (= :failure.class/timeout
           (fc/classify-exception (java.net.SocketTimeoutException. "Read timed out"))))
    (is (= :failure.class/external
           (fc/classify-exception (java.net.ConnectException. "Connection refused"))))
    (is (= :failure.class/external
           (fc/classify-exception (java.net.UnknownHostException. "host.invalid"))))))

;; ---------------------------------------------------------------------------- Fallback to unknown

(deftest classify-unknown-test
  (testing "unrecognizable failures fall back to unknown"
    (is (= :failure.class/unknown
           (fc/classify {:error/message "Something mysterious happened"})))
    (is (= :failure.class/unknown
           (fc/classify {})))
    (is (= :failure.class/unknown
           (fc/classify {:error/message nil})))))

;; ---------------------------------------------------------------------------- Priority ordering

(deftest classify-anomaly-takes-precedence-test
  (testing "anomaly category takes precedence over message"
    (is (= :failure.class/timeout
           (fc/classify {:anomaly/category :anomalies/timeout
                         :error/message "Some agent error detected"})))))

;; ---------------------------------------------------------------------------- Property: always returns valid class

(deftest classify-always-returns-valid-class-test
  (testing "classify always returns a member of the canonical set"
    (let [test-inputs [{:error/message "timeout"}
                       {:error/message "random noise xyz123"}
                       {:anomaly/category :anomalies/fault}
                       {:anomaly/category :anomalies/timeout}
                       {:exception/class "java.lang.RuntimeException"
                        :error/message "oops"}
                       {}
                       nil]]
      (doseq [input test-inputs]
        (let [result (fc/classify (or input {}))]
          (is (fc/valid-failure-class? result)
              (str "classify returned invalid class " result
                   " for input " (pr-str input))))))))
