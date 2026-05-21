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

(ns ai.miniforge.workflow.execute-enter-error-propagation-test
  "Phase-enter exceptions must populate `:execution/errors` so the
   downstream `workflow/failed` event surfaces the underlying message
   and ex-data. Regression coverage for the G10 residual where a
   throwing `validate-required-inputs!` produced an empty
   `{:errors []}` payload and the bridge reported `\"Workflow failed:
   no error message\"`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.execution :as exec]))

(def ^:private test-phase-name :phase-under-test)
(def ^:private test-error-message "preflight check rejected input")
(def ^:private test-error-data {:input-key :career/growth-framework-payload})

(defn- throwing-enter-fn
  []
  (fn [_ctx]
    (throw (ex-info test-error-message test-error-data))))

(defn- interceptor-with-error-handler
  "Build an interceptor that mimics the career-side `fail-phase`
   convention: its `:error` handler only writes into the per-phase
   `:phase` map. Before the G10 fix the workflow-level
   `:execution/errors` accumulator stayed empty for this shape."
  []
  {:name :test
   :config {:phase test-phase-name}
   :enter (throwing-enter-fn)
   :error (fn [ctx ex]
            (-> ctx
                (assoc-in [:phase :status] :failed)
                (assoc-in [:phase :error]
                          {:message (ex-message ex)
                           :data (ex-data ex)})))})

(defn- interceptor-without-error-handler
  []
  {:name :test
   :config {:phase test-phase-name}
   :enter (throwing-enter-fn)})

(defn- empty-execution-context
  []
  {:execution/errors []
   :execution/response-chain (response/create :test-workflow)})

(deftest enter-throw-with-error-handler-populates-execution-errors-test
  (testing "phase-enter throw appends to :execution/errors even when an :error handler is set"
    (let [interceptor (interceptor-with-error-handler)
          result (exec/execute-enter interceptor (empty-execution-context))
          errors (:execution/errors result)
          first-error (first errors)]
      (is (= 1 (count errors))
          "exactly one error record should be appended for a single throw")
      (is (= :phase-error (:type first-error))
          "error record should carry the canonical :phase-error type")
      (is (= test-phase-name (:phase first-error))
          "error record should name the failing phase")
      (is (= test-error-message (:message first-error))
          "error record should carry the thrown exception's message")
      (is (= test-error-data (:data first-error))
          "error record should carry the thrown exception's ex-data")
      (is (= :failed (get-in result [:phase :status]))
          ":error handler should still run and mark the phase failed"))))

(deftest enter-throw-without-error-handler-populates-execution-errors-test
  (testing "phase-enter throw appends to :execution/errors when no :error handler is set"
    (let [interceptor (interceptor-without-error-handler)
          result (exec/execute-enter interceptor (empty-execution-context))
          errors (:execution/errors result)
          first-error (first errors)]
      (is (= 1 (count errors)))
      (is (= test-error-message (:message first-error)))
      (is (= test-error-data (:data first-error))))))
