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

(ns ai.miniforge.agent.response-parsing-test
  "Integration test for agent response parsing and validation.

  Tests that agent responses are correctly structured and can be consumed
  by workflow phases without running real LLM calls."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.edn :as edn]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Mock Data

(def mock-llm-plan-response
  "Mock LLM response for a plan generation request."
  {:role "assistant"
   :content "```clojure
{:plan/id #uuid \"a1b2c3d4-e5f6-7890-1234-567890abcdef\"
 :plan/tasks
 [{:task/id \"task-1\"
   :task/description \"Implement feature X\"
   :task/dependencies []
   :task/acceptance-criteria [\"Feature works\" \"Tests pass\"]
   :task/estimated-effort :medium}
  {:task/id \"task-2\"
   :task/description \"Add tests for feature X\"
   :task/dependencies [\"task-1\"]
   :task/acceptance-criteria [\"Tests cover edge cases\"]
   :task/estimated-effort :small}]}
```"})

(def mock-llm-code-response
  "Mock LLM response for code generation."
  {:role "assistant"
   :content "```clojure
{:code/id #uuid \"11111111-1111-1111-1111-111111111111\"
 :code/files
 [{:path \"src/feature.clj\"
   :content \"(ns feature)\\n(defn new-feature [] :ok)\"
   :action :create}
  {:path \"test/feature_test.clj\"
   :content \"(ns feature-test)\\n(deftest test-new-feature (is true))\"
   :action :create}]
 :code/language \"clojure\"}
```"})

(def mock-llm-test-response
  "Mock LLM response for test generation."
  {:role "assistant"
   :content "```clojure
{:test/id #uuid \"22222222-2222-2222-2222-222222222222\"
 :test/files
 [{:path \"test/feature_test.clj\"
   :content \"(ns feature-test (:require [clojure.test :refer :all]))\"}]
 :test/type :unit
 :test/coverage {:lines 90.0 :branches 85.0}
 :test/framework \"clojure.test\"}
```"})

(def mock-llm-review-response
  "Mock LLM response for code review."
  {:role "assistant"
   :content "```clojure
{:review/id #uuid \"33333333-3333-3333-3333-333333333333\"
 :review/approved? true
 :review/comments
 [{:file \"src/feature.clj\"
   :line 10
   :severity :info
   :message \"Consider adding error handling\"}]
 :review/quality-score 8.5}
```"})

(def mock-llm-malformed-response
  "Mock LLM response with malformed EDN."
  {:role "assistant"
   :content "```clojure
{:code/id \"not-a-uuid\"
 :code/files [\"this is wrong\"]}
```"})

(def mock-llm-missing-fields-response
  "Mock LLM response missing required fields."
  {:role "assistant"
   :content "```clojure
{:code/id #uuid \"44444444-4444-4444-4444-444444444444\"}
```"})

;------------------------------------------------------------------------------ Mock Functions

(defn mock-llm-backend
  "Create a mock LLM backend that returns predefined responses."
  [response-map]
  (fn [messages _opts]
    (let [last-message (last messages)
          role (:role last-message)]
      ;; Return appropriate mock based on request context
      (or (get response-map role)
          mock-llm-code-response))))

(defn extract-code-block
  "Extract Clojure code block from markdown response."
  [content]
  (when-let [match (re-find #"(?s)```clojure\n(.*?)\n```" content)]
    (second match)))

;------------------------------------------------------------------------------ Parser Helpers

(defn parse-edn
  "Parse EDN string into Clojure data structure."
  [edn-str]
  (when edn-str
    (try
      (edn/read-string edn-str)
      (catch Exception _e
        nil))))

(defn parse-uuid-str
  "Parse UUID string."
  [uuid-str]
  (when uuid-str
    (java.util.UUID/fromString uuid-str)))

(defn valid-file-action?
  "Check if file action is valid."
  [action]
  (contains? #{:create :modify :delete} action))

(defn validate-code-artifact
  "Validate code artifact structure."
  [artifact]
  (let [has-id? (some? (:code/id artifact))
        has-files? (vector? (:code/files artifact))
        files-valid? (and has-files?
                          (every? (fn [f]
                                    (and (string? (:path f))
                                         (or (string? (:content f))
                                             (= :delete (:action f)))
                                         (valid-file-action? (:action f))))
                                  (:code/files artifact)))]
    {:valid? (and has-id? has-files? files-valid?)
     :errors (cond-> []
               (not has-id?) (conj "Missing :code/id")
               (not has-files?) (conj "Missing or invalid :code/files")
               (and has-files? (not files-valid?)) (conj "Invalid file specifications"))}))

;------------------------------------------------------------------------------ Tests

(deftest parse-plan-response-test
  (testing "Parsing plan response from LLM"
    (let [code-block (extract-code-block (:content mock-llm-plan-response))
          parsed (parse-edn code-block)]

      (is (some? parsed)
          "Should successfully parse plan EDN")
      (is (uuid? (:plan/id parsed))
          "Plan ID should be a UUID")
      (is (vector? (:plan/tasks parsed))
          "Tasks should be a vector")
      (is (= 2 (count (:plan/tasks parsed)))
          "Should have 2 tasks")
      (is (every? :task/id (:plan/tasks parsed))
          "All tasks should have IDs")
      (is (every? :task/description (:plan/tasks parsed))
          "All tasks should have descriptions"))))

(deftest parse-code-response-test
  (testing "Parsing code artifact response from LLM"
    (let [code-block (extract-code-block (:content mock-llm-code-response))
          parsed (parse-edn code-block)]

      (is (some? parsed)
          "Should successfully parse code EDN")
      (is (uuid? (:code/id parsed))
          "Code ID should be a UUID")
      (is (vector? (:code/files parsed))
          "Files should be a vector")
      (is (= 2 (count (:code/files parsed)))
          "Should have 2 files")
      (is (every? :path (:code/files parsed))
          "All files should have paths")
      (is (every? :content (:code/files parsed))
          "All files should have content")
      (is (every? :action (:code/files parsed))
          "All files should have actions")
      (is (= "clojure" (:code/language parsed))
          "Should specify language"))))

(deftest parse-test-response-test
  (testing "Parsing test artifact response from LLM"
    (let [code-block (extract-code-block (:content mock-llm-test-response))
          parsed (parse-edn code-block)]

      (is (some? parsed)
          "Should successfully parse test EDN")
      (is (uuid? (:test/id parsed))
          "Test ID should be a UUID")
      (is (vector? (:test/files parsed))
          "Test files should be a vector")
      (is (= :unit (:test/type parsed))
          "Should specify test type")
      (is (map? (:test/coverage parsed))
          "Coverage should be a map")
      (is (number? (get-in parsed [:test/coverage :lines]))
          "Should have line coverage percentage"))))

(deftest parse-review-response-test
  (testing "Parsing review response from LLM"
    (let [code-block (extract-code-block (:content mock-llm-review-response))
          parsed (parse-edn code-block)]

      (is (some? parsed)
          "Should successfully parse review EDN")
      (is (uuid? (:review/id parsed))
          "Review ID should be a UUID")
      (is (boolean? (:review/approved? parsed))
          "Should have boolean approval status")
      (is (vector? (:review/comments parsed))
          "Comments should be a vector")
      (is (number? (:review/quality-score parsed))
          "Should have numeric quality score"))))

(deftest malformed-response-handling-test
  (testing "Handling malformed EDN in response"
    (let [code-block (extract-code-block (:content mock-llm-malformed-response))
          result (try
                   (parse-edn code-block)
                   (catch Exception _e
                     {:error (ex-message _e)}))]

      ;; Should either return error or handle gracefully
      (is (or (map? result)
              (nil? result))
          "Should handle malformed EDN without crashing"))))

(deftest missing-required-fields-test
  (testing "Detecting missing required fields in response"
    (let [code-block (extract-code-block (:content mock-llm-missing-fields-response))
          parsed (parse-edn code-block)]

      (is (some? parsed)
          "Should parse even with missing fields")
      (is (uuid? (:code/id parsed))
          "Should have ID")
      (is (nil? (:code/files parsed))
          "Should detect missing files field")

      ;; Validation should catch this
      (let [validation-result (validate-code-artifact parsed)]
        (is (false? (:valid? validation-result))
            "Validation should fail for missing required fields")))))

(deftest agent-response-to-phase-integration-test
  (testing "Agent response can be consumed by workflow phase"
    (let [code-block (extract-code-block (:content mock-llm-code-response))
          parsed (parse-edn code-block)
          ;; Simulate what a phase does with the response
          phase-result (response/success parsed {:tokens 1000 :duration-ms 5000})]

      (is (response/success? phase-result)
          "Phase should successfully process agent response")
      (is (= parsed (:output phase-result))
          "Phase should extract parsed artifact")
      (is (map? (:metrics phase-result))
          "Phase should have metrics"))))

(deftest response-wrapper-structure-test
  (testing "Agent responses are properly wrapped in response protocol"
    (let [artifact {:code/id (random-uuid)
                    :code/files []}
          success-response (response/success artifact {:tokens 500})
          failure-response (response/failure (ex-info "Test error" {}))]

      ;; Test success response structure
      (is (response/success? success-response)
          "Success response should have :success status")
      (is (= artifact (:output success-response))
          "Success response should contain artifact")
      (is (map? (:metrics success-response))
          "Success response should have metrics")

      ;; Test failure response structure
      (is (false? (:success failure-response))
          "Failure response should have :success false")
      (is (string? (get-in failure-response [:error :message]))
          "Failure response should have error message"))))

(deftest schema-validation-integration-test
  (testing "Parsed responses are validated against schema"
    (let [valid-code (parse-edn (extract-code-block (:content mock-llm-code-response)))
          invalid-code {:code/files "should-be-vector"}]

      ;; Valid artifact should pass
      (let [valid-result (validate-code-artifact valid-code)]
        (is (true? (:valid? valid-result))
            "Valid artifact should pass validation"))

      ;; Invalid artifact should fail
      (let [invalid-result (validate-code-artifact invalid-code)]
        (is (false? (:valid? invalid-result))
            "Invalid artifact should fail validation")
        (is (seq (:errors invalid-result))
            "Validation should provide error details")))))

(deftest file-action-validation-test
  (testing "File actions are validated"
    (let [valid-actions [:create :modify :delete]
          invalid-action :invalid]

      (doseq [action valid-actions]
        (is (valid-file-action? action)
            (str "Action " action " should be valid")))

      (is (not (valid-file-action? invalid-action))
          "Invalid action should be rejected"))))

(deftest uuid-parsing-test
  (testing "UUID strings are parsed correctly"
    (let [uuid-str "a1b2c3d4-e5f6-7890-1234-567890abcdef"
          parsed (parse-uuid-str uuid-str)]

      (is (uuid? parsed)
          "Should parse valid UUID string")
      (is (= uuid-str (str parsed))
          "Parsed UUID should match original")))

  (testing "Invalid UUIDs are handled"
    (let [invalid-uuid "not-a-uuid"
          result (try
                   (parse-uuid-str invalid-uuid)
                   (catch Exception _e
                     :error))]

      (is (= :error result)
          "Should handle invalid UUID gracefully"))))

(deftest nested-structure-parsing-test
  (testing "Deeply nested response structures are parsed correctly"
    (let [nested-response {:plan/id (random-uuid)
                           :plan/tasks
                           [{:task/id "t1"
                             :task/subtasks
                             [{:subtask/id "st1"
                               :subtask/checks
                               [{:check/name "validate"
                                 :check/params {:strict true}}]}]}]}]

      (is (uuid? (:plan/id nested-response))
          "Top-level UUID should be valid")
      (is (= "t1" (get-in nested-response [:plan/tasks 0 :task/id]))
          "Should access nested task ID")
      (is (= {:strict true}
             (get-in nested-response [:plan/tasks 0 :task/subtasks 0
                                      :subtask/checks 0 :check/params]))
          "Should access deeply nested params"))))

(deftest error-message-extraction-test
  (testing "Error messages are extracted from failed responses"
    (let [error (ex-info "Parsing failed" {:line 10 :column 5})
          failure-response (response/failure error)]

      (is (= "Parsing failed" (get-in failure-response [:error :message]))
          "Should extract error message")
      (is (map? (get-in failure-response [:error :data]))
          "Should preserve error data"))))

(deftest metrics-accumulation-test
  (testing "Metrics from multiple agent calls accumulate correctly"
    (let [response1 (response/success {} {:tokens 100 :duration-ms 1000})
          response2 (response/success {} {:tokens 200 :duration-ms 2000})
          combined-metrics (merge-with + (:metrics response1) (:metrics response2))]

      (is (= 300 (:tokens combined-metrics))
          "Tokens should sum correctly")
      (is (= 3000 (:duration-ms combined-metrics))
          "Duration should sum correctly"))))

(deftest response-idempotency-test
  (testing "Parsing the same response multiple times yields same result"
    (let [code-block (extract-code-block (:content mock-llm-code-response))
          parsed1 (parse-edn code-block)
          parsed2 (parse-edn code-block)]

      (is (= parsed1 parsed2)
          "Parsing should be idempotent"))))
