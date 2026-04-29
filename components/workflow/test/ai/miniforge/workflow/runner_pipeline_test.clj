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

(ns ai.miniforge.workflow.runner-pipeline-test
  "Tests for the extracted pipeline stages in runner.clj.

   Layer 0: Factories
   Layer 1: Tests"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.runner :as runner]
   [ai.miniforge.workflow.runner-cleanup :as cleanup]))

;------------------------------------------------------------------------------ Layer 0
;; Factories — access private fns via var

(def ^:private validate-completion #'runner/validate-completion)
(def ^:private execute-pipeline-loop #'runner/execute-pipeline-loop)
(def ^:private post-workflow-cleanup! cleanup/post-workflow-cleanup!)
(def ^:private wrap-phase-callbacks #'runner/wrap-phase-callbacks)
(def ^:private acquire-environment #'runner/acquire-environment)

(defn- make-ctx
  "Create a minimal execution context for testing."
  [& {:keys [status artifacts phase-results]
      :or {status :completed artifacts [] phase-results {}}}]
  {:execution/status status
   :execution/artifacts artifacts
   :execution/phase-results phase-results
   :execution/errors []})

;------------------------------------------------------------------------------ Layer 1
;; Tests

(deftest validate-completion-passes-normal-test
  (testing "context with results passes through unchanged"
    (let [ctx (make-ctx :phase-results {:plan {:result :ok}})]
      (is (= ctx (validate-completion ctx))))))

(deftest validate-completion-warns-on-empty-test
  (testing "succeeded context with no results gets warning"
    (let [ctx (make-ctx :status :completed :artifacts [] :phase-results {})
          result (validate-completion ctx)]
      (is (= :completed-with-warnings (:execution/status result)))
      (is (= 1 (count (:execution/errors result)))))))

(deftest validate-completion-ignores-failed-test
  (testing "failed context with no results is not warned"
    (let [ctx (make-ctx :status :failed)]
      (is (= :failed (:execution/status (validate-completion ctx)))))))

(deftest execute-pipeline-loop-empty-test
  (testing "empty pipeline returns empty-pipeline result"
    (let [ctx (make-ctx)
          result (execute-pipeline-loop [] ctx {} (atom {}) 50)]
      (is (some? result)))))

(deftest wrap-phase-callbacks-creates-both-keys-test
  (testing "wraps both on-phase-start and on-phase-complete"
    (let [callbacks (wrap-phase-callbacks nil {})]
      (is (fn? (:on-phase-start callbacks)))
      (is (fn? (:on-phase-complete callbacks))))))

(deftest post-workflow-cleanup-survives-nil-test
  (testing "cleanup handles all-nil args without throwing"
    (is (nil? (post-workflow-cleanup! {} nil nil nil nil)))))

(deftest post-workflow-cleanup-calls-observe-signal-test
  (testing "cleanup calls observe-signal-fn when present"
    (let [called (atom false)
          opts {:observe-signal-fn (fn [_] (reset! called true))}]
      (post-workflow-cleanup! opts (make-ctx) {:workflow/id :test} nil nil)
      (is @called))))

(deftest acquire-environment-skips-when-pre-acquired-test
  (testing "returns nil acquired-env when executor already provided"
    (let [opts {:executor :mock :environment-id "env-1"}
          [acquired opts'] (acquire-environment {} {} opts)]
      (is (nil? acquired))
      (is (= :mock (:executor opts'))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (validate-completion (make-ctx :status :completed))

  :leave-this-here)
