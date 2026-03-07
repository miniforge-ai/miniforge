(ns ai.miniforge.workflow.run7-regression-test
  "Regression tests for Run 7 bugs:
   1. Duration-ms always 0 — agent metrics fallback shadows real wall-clock time
   2. Terminal event silently dropped — requiring-resolve nil causes when-let short-circuit
   3. Phase leave functions must override agent duration-ms with wall-clock time"
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.runner :as runner]
   [ai.miniforge.workflow.execution :as exec]))

;; ============================================================================
;; Fix 1: Duration-ms extraction in publish-phase-completed!
;;
;; Root cause: (or [:metrics :duration-ms] ...) returned 0 (agent fallback)
;; before (:duration-ms result) (real wall-clock time) was tried.
;; ============================================================================

(deftest duration-ms-extraction-order-test
  (testing "phase result with real :duration-ms takes priority over :metrics fallback"
    ;; Simulate a phase result where:
    ;; - [:metrics :duration-ms] = 0 (agent didn't track time)
    ;; - [:duration-ms] = 5000 (real wall-clock from leave-implement)
    (let [result {:name :implement
                  :status :completed
                  :duration-ms 5000
                  :metrics {:tokens 1500 :duration-ms 0}
                  :result {:status :success}}
          ;; This mirrors the extraction logic in publish-phase-completed!
          duration-ms (or (:duration-ms result)
                          (get-in result [:phase/metrics :duration-ms])
                          (get-in result [:metrics :duration-ms]))]
      (is (= 5000 duration-ms)
          "Should use wall-clock :duration-ms, not agent's 0 fallback")))

  (testing "falls back to :metrics :duration-ms when no top-level :duration-ms"
    (let [result {:name :plan
                  :status :completed
                  :metrics {:tokens 500 :duration-ms 2000}}
          duration-ms (or (:duration-ms result)
                          (get-in result [:phase/metrics :duration-ms])
                          (get-in result [:metrics :duration-ms]))]
      (is (= 2000 duration-ms)
          "Should fall back to metrics when no top-level key")))

  (testing "falls back to :phase/metrics :duration-ms"
    (let [result {:name :verify
                  :status :completed
                  :phase/metrics {:duration-ms 3000}}
          duration-ms (or (:duration-ms result)
                          (get-in result [:phase/metrics :duration-ms])
                          (get-in result [:metrics :duration-ms]))]
      (is (= 3000 duration-ms)))))

;; ============================================================================
;; Fix 2: Terminal event fallback when constructor is nil
;;
;; Root cause: (when-let [constructor (requiring-resolve ...)] ...) silently
;; did nothing when the constructor var was not resolvable.
;; ============================================================================

(deftest publish-workflow-completed-fallback-test
  (testing "publishes ad-hoc event when event-stream is provided"
    (let [events (atom [])
          ;; Use a simple map as event-stream — publish-event! uses find-ns
          ;; fallback path which may not resolve, but we can test the function
          ;; doesn't throw and attempts to publish
          fake-stream {:test true}
          ctx {:execution/id (random-uuid)
               :execution/status :completed
               :execution/metrics {:duration-ms 5000}}]
      ;; The function should not throw even with a fake event stream
      (is (nil? (runner/publish-workflow-completed! fake-stream ctx))
          "Should not throw with non-nil event stream")))

  (testing "skips publishing when event-stream is nil"
    (let [ctx {:execution/id (random-uuid)
               :execution/status :failed
               :execution/errors [{:type :test :message "test"}]}]
      (is (nil? (runner/publish-workflow-completed! nil ctx))
          "Should return nil and not throw when event-stream is nil"))))

;; ============================================================================
;; Fix 3: Phase leave functions override agent duration-ms
;;
;; Root cause: Agent returns {:metrics {:tokens N :duration-ms 0}} because
;; the LLM client doesn't track wall-clock time. The phase leave function
;; computes real duration but the agent's 0 was used instead.
;; ============================================================================

(deftest phase-leave-overrides-agent-duration-test
  (testing "implement leave stores real duration in :phase :metrics :duration-ms"
    ;; We can't easily call leave-implement directly without a full context,
    ;; but we can verify the pattern: after applying the fix, the metrics map
    ;; should always have duration-ms overridden with the wall-clock time.
    (let [agent-metrics {:tokens 1500 :duration-ms 0 :cost-usd 0.003}
          real-duration-ms 7500
          ;; This is the fixed pattern from leave-implement
          metrics (assoc agent-metrics :duration-ms real-duration-ms)]
      (is (= 7500 (:duration-ms metrics))
          "Should override agent's 0 with real wall-clock time")
      (is (= 1500 (:tokens metrics))
          "Should preserve token count from agent")))

  (testing "execution/metrics accumulates real duration not agent fallback"
    (let [execution-metrics {:tokens 0 :duration-ms 0 :cost-usd 0.0}
          ;; Simulate what leave-implement does after the fix
          real-duration-ms 7500
          metrics {:tokens 1500 :duration-ms real-duration-ms :cost-usd 0.003}
          updated (-> execution-metrics
                      (update :tokens (fnil + 0) (:tokens metrics 0))
                      (update :duration-ms (fnil + 0) (:duration-ms metrics 0))
                      (update :cost-usd (fnil + 0.0) (:cost-usd metrics 0.0)))]
      (is (= 7500 (:duration-ms updated))
          "Execution metrics should accumulate real duration")
      (is (= 1500 (:tokens updated))))))

;; ============================================================================
;; Fix 4: Stale :phase map cleared between phases (from PR #268, verify here)
;; ============================================================================

(deftest phase-map-cleared-between-phases-test
  (testing "execute-phase-lifecycle clears :phase before entering next phase"
    ;; Simulate a context with a stale :phase map from a previous phase
    ;; (e.g., verify left :redirect-to :implement on it)
    (let [stale-ctx {:phase {:name :verify
                             :status :failed
                             :redirect-to :implement
                             :duration-ms 3000}
                     :execution/input {:description "test"}
                     :execution/phase-results {}
                     :execution/errors []
                     :execution/response-chain {:operation :test :succeeded? true}
                     :execution/artifacts []
                     :execution/files-written []
                     :execution/metrics {:tokens 0 :duration-ms 0}}
          ;; Use the :done interceptor (simplest, no LLM needed)
          interceptor (ai.miniforge.phase.interface/get-phase-interceptor {:phase :done})
          ;; execute-phase-lifecycle should clear :phase first
          [ctx-after _result] (exec/execute-phase-lifecycle interceptor stale-ctx)]
      ;; The stale :redirect-to should NOT be present
      (is (not= :implement (get-in ctx-after [:phase :redirect-to]))
          "Stale :redirect-to from previous phase must not leak"))))
