;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.workflow.runner-vocabulary-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.dag-executor.interface :as dag-exec]
            [ai.miniforge.workflow.execution :as execution]
            [ai.miniforge.workflow.monitoring :as monitoring]
            [ai.miniforge.workflow.runner-callbacks :as callbacks]
            [ai.miniforge.workflow.runner-defaults :as defaults]
            [ai.miniforge.workflow.runner-events :as events]
            [ai.miniforge.workflow.runner-environment :as environment]
            [ai.miniforge.workflow.runner-iteration :as iteration]
            [ai.miniforge.workflow.runner-loop :as pipeline-loop]
            [ai.miniforge.workflow.runner-outcome :as outcome]
            [ai.miniforge.workflow.runner-phase-step :as phase-step]
            [ai.miniforge.workflow.runner-preparation :as preparation]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} workflow
  {:workflow/id :test :workflow/version "1.0.0" :workflow/pipeline []})

(defn- ^{:stratum 0} recording [calls action result]
  (fn [& args] (swap! calls conj [action args]) result))

(defn- ^{:stratum 0} stuck-context []
  {:execution/status :running
   :execution/current-phase :implement
   :execution/phase-results {}
   :execution/errors []
   :execution/response-chain {}
   :execution/started-at (System/currentTimeMillis)})

(deftest ^{:stratum 0} runtime-class-checks-the-executor-contract
  (doseq [[executor expected]
          [[nil nil] [:unsupported nil]
           [(reify dag-exec/TaskExecutor (executor-type [_] :worktree)) :worktree]]]
    (is (= expected (get-in (outcome/extract-output {:execution/executor executor})
                            [:execution/output :evidence/runtime-class])))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} context-keeps-explicit-execution-port
  (let [port (constantly :nested-result)
        context (preparation/build-initial-context workflow {} {} port)]
    (is (not (anomaly/anomaly? context)))
    (is (identical? port (:execution/run-pipeline-fn context)))
    (is (= :local (:execution/mode context)))
    (is (empty? (preparation/build-pipeline workflow)))
    (is (anomaly/anomaly?
          (preparation/build-initial-context workflow {} {:execution-mode :governed} port)))))

(deftest ^{:stratum 1} callbacks-preserve-order-and-caller-arguments
  (let [calls (atom [])
        context {:execution/id (random-uuid)}
        interceptor {:config {:phase :verify}}
        result {:status :success}
        wrapped (callbacks/wrap-phase-callbacks
                  nil {:on-phase-start (recording calls :caller-started nil)
                       :on-phase-complete (recording calls :caller-completed nil)})]
    (with-redefs [events/publish-phase-started! (recording calls :started nil)
                  events/start-phase-heartbeat! (recording calls :heartbeat-started :handle)
                  events/stop-phase-heartbeat! (recording calls :heartbeat-stopped nil)
                  events/publish-phase-completed! (recording calls :completed nil)]
      ((:on-phase-start wrapped) context interceptor)
      ((:on-phase-complete wrapped) context interceptor result)
      ((:stop-phase-heartbeats! wrapped)))
    (is (= [:started :heartbeat-started :caller-started
             :heartbeat-stopped :completed :caller-completed]
            (mapv first @calls)))
    (is (= [context interceptor] (second (nth @calls 2))))
    (is (= [context interceptor result] (second (last @calls))))))

(deftest ^{:stratum 1} loop-stops-consecutive-retries
  (let [calls (atom 0)
        context (stuck-context)]
    (with-redefs [defaults/max-consecutive-phase-retries (constantly 2)
                  iteration/execute-single-iteration
                  (fn [_ context _ _ _] (swap! calls inc) context)]
      (let [result (pipeline-loop/execute-pipeline-loop
                     [{:phase :implement}] context {} (atom {}) 50)]
        (is (= :failed (:execution/status result)))
        (is (= 2 @calls))
        (is (= :phase-retries-exhausted (-> result :execution/errors last :type)))))))

(deftest ^{:stratum 1} phase-policy-persists-before-health-and-stops-on-rate-limits
  (doseq [[message status order]
          [["ordinary diagnostic" :running [:phase :persist :health]]
           ["HTTP 429 rate limit" :failed [:phase :persist]]]]
    (let [calls (atom [])
          context (assoc-in (stuck-context)
                             [:execution/phase-results :implement :error :message] message)]
      (with-redefs [execution/execute-phase-step (recording calls :phase context)
                    monitoring/clear-transient-state identity
                    environment/persist-workspace-at-phase-boundary! (recording calls :persist nil)
                    events/check-backend-health-at-boundary! (recording calls :health nil)]
        (let [result (phase-step/execute-phase [] context {})]
          (is (= status (:execution/status result)))
          (is (= order (mapv first @calls)))
          (when (= :failed status)
            (is (= :rate-limited (-> result :execution/errors last :type)))))))))

(comment ::runner-vocabulary-contracts)
