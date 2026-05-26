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
   [ai.miniforge.workflow.runner-events :as events]
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

(deftest execute-pipeline-loop-aborts-on-consecutive-phase-retries-test
  (testing "a phase that re-runs every iteration aborts loud well before max-phases (the overnight runaway guard)"
    (let [stuck-ctx {:execution/status        :running   ; non-terminal so the loop proceeds
                     :execution/current-phase :implement ; never advances → in-place retry
                     :execution/phase-results {}
                     :execution/errors        []
                     :execution/response-chain {}
                     :execution/started-at    (System/currentTimeMillis)}
          calls     (atom 0)]
      ;; Stub one iteration to return the same stuck (same-phase, non-terminal)
      ;; context, simulating implement failing + re-entering forever.
      (with-redefs [runner/execute-single-iteration
                    (fn [_pipeline ctx _callbacks _iteration _control] (swap! calls inc) ctx)]
        (let [result (execute-pipeline-loop [{:phase :implement}] stuck-ctx {} (atom {}) 50)]
          (is (= :failed (:execution/status result))
              "the loop must terminate the workflow, not spin to max-phases")
          (is (some #(= :phase-retries-exhausted (:type %)) (:execution/errors result))
              "failure is the typed consecutive-retry anomaly, not max-phases")
          ;; Default cap is 3 — must abort within a handful of iterations, nowhere near 50.
          (is (<= @calls 3)
              (str "expected ≤3 iterations before the breaker fired, got " @calls)))))))

(deftest wrap-phase-callbacks-creates-both-keys-test
  (testing "wraps both on-phase-start and on-phase-complete"
    (let [callbacks (wrap-phase-callbacks nil {})]
      (is (fn? (:on-phase-start callbacks)))
      (is (fn? (:on-phase-complete callbacks)))
      (is (fn? (:stop-phase-heartbeats! callbacks))))))

(deftest wrap-phase-callbacks-stops-heartbeat-on-complete-test
  (let [stopped (atom [])
        callbacks (wrap-phase-callbacks nil {})
        ctx {:execution/id (random-uuid)}
        interceptor {:phase :implement}]
    (with-redefs [events/publish-phase-started! (constantly nil)
                  events/publish-phase-completed! (constantly nil)
                  events/start-phase-heartbeat! (fn [& _] :handle)
                  events/stop-phase-heartbeat! #(swap! stopped conj %)]
      ((:on-phase-start callbacks) ctx interceptor)
      ((:on-phase-complete callbacks) ctx interceptor {:status :success}))
    (is (= [:handle] @stopped))))

(deftest wrap-phase-callbacks-stop-all-cleans-active-heartbeats-test
  (let [stopped (atom [])
        callbacks (wrap-phase-callbacks nil {})]
    (with-redefs [events/publish-phase-started! (constantly nil)
                  events/start-phase-heartbeat! (fn [& _] :handle)
                  events/stop-phase-heartbeat! #(swap! stopped conj %)]
      ((:on-phase-start callbacks) {:execution/id (random-uuid)} {:phase :verify})
      ((:stop-phase-heartbeats! callbacks)))
    (is (= [:handle] @stopped))))

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
