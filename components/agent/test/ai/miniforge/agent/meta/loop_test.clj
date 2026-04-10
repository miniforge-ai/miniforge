;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.agent.meta.loop-test
  "Integration test for the meta-agent loop.

   Tests the full closed-loop cycle: reliability → diagnosis → improvement."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.meta.loop :as meta-loop]
   [ai.miniforge.reliability.interface :as rel]
   [ai.miniforge.diagnosis.interface :as diag]
   [ai.miniforge.improvement.interface :as imp]
   [ai.miniforge.event-stream.core :as events]))

(def now (java.util.Date.))

(defn- make-stream []
  (atom {:events [] :subscribers {} :filters {} :sequence-numbers {} :sinks []}))

;; ---------------------------------------------------------------------------- Full cycle test

(deftest meta-loop-cycle-test
  (testing "full meta-loop cycle with unhealthy metrics"
    (let [stream (make-stream)
          engine (rel/create-engine stream {:windows [:7d] :tiers [:standard]})
          deg-mgr (rel/create-degradation-manager stream)
          pipeline (imp/create-pipeline)
          ctx (meta-loop/create-meta-loop-context
               {:reliability-engine engine
                :degradation-manager deg-mgr
                :improvement-pipeline pipeline
                :event-stream stream})

          ;; Simulate unhealthy fleet: 50% failure rate + recurring timeouts
          metrics {:workflow-metrics [{:status :completed :timestamp now}
                                     {:status :failed :timestamp now}
                                     {:status :failed :timestamp now}
                                     {:status :completed :timestamp now}]}

          failure-events (repeat 5 {:failure/class :failure.class/timeout
                                     :timestamp now})

          result (meta-loop/run-meta-loop-cycle!
                  ctx
                  metrics
                  {:failure-events failure-events
                   :phase-metrics [{:phase :implement :iterations 5 :timestamp now}
                                   {:phase :implement :iterations 4 :timestamp now}]})]

      ;; Should complete successfully
      (is (= 1 (:cycle-number result)))
      (is (map? (:reliability result)))
      (is (keyword? (:degradation-mode result)))

      ;; Should detect signals from the bad metrics
      (is (pos? (:signals result)))

      ;; Should generate diagnoses
      (is (pos? (:diagnoses result)))

      ;; Should generate proposals
      (is (pos? (:proposals result)))

      ;; Pipeline should have stored proposals
      (is (pos? (count (imp/get-proposals pipeline))))

      ;; Events should have been emitted (SLIs + meta-loop summary)
      (is (pos? (count (:events @stream)))))))

(deftest meta-loop-healthy-cycle-test
  (testing "healthy metrics with no failures produce fewer signals"
    (let [stream (make-stream)
          engine (rel/create-engine stream {:windows [:7d] :tiers [:standard]})
          ctx (meta-loop/create-meta-loop-context
               {:reliability-engine engine
                :event-stream stream})

          ;; All successful, good gate pass, no failures
          metrics {:workflow-metrics (repeat 10 {:status :completed :timestamp now})
                   :gate-metrics (repeat 10 {:passed? true :timestamp now})
                   :tool-metrics (repeat 10 {:success? true :timestamp now})}

          result (meta-loop/run-meta-loop-cycle!
                  ctx
                  metrics
                  {:failure-events []
                   :phase-metrics (repeat 10 {:phase :implement :iterations 1
                                               :success? true :timestamp now})})]

      (is (= 1 (:cycle-number result)))
      ;; With all-green metrics, no SLO breaches, no failure patterns
      ;; Fewer or no signals expected
      (is (<= (:signals result) (:signals
                                  (meta-loop/run-meta-loop-cycle!
                                   (meta-loop/create-meta-loop-context {:event-stream (make-stream)})
                                   {:workflow-metrics [{:status :failed :timestamp now}
                                                       {:status :failed :timestamp now}]}
                                   {:failure-events (repeat 5 {:failure/class :failure.class/timeout
                                                                :timestamp now})})))))))

(deftest meta-loop-safe-mode-skips-proposals-test
  (testing "safe-mode skips proposal generation"
    (let [stream (make-stream)
          engine (rel/create-engine stream {:windows [:7d] :tiers [:critical]})
          deg-mgr (rel/create-degradation-manager stream)
          pipeline (imp/create-pipeline)

          ;; Force safe-mode
          _ (rel/enter-safe-mode! deg-mgr :emergency-stop "Test")

          ctx (meta-loop/create-meta-loop-context
               {:reliability-engine engine
                :degradation-manager deg-mgr
                :improvement-pipeline pipeline
                :event-stream stream})

          metrics {:workflow-metrics [{:status :failed :timestamp now}
                                     {:status :failed :timestamp now}]}

          result (meta-loop/run-meta-loop-cycle!
                  ctx
                  metrics
                  {:failure-events (repeat 5 {:failure/class :failure.class/timeout
                                               :timestamp now})})]

      ;; Signals and diagnoses should still be generated
      (is (pos? (:signals result)))
      ;; But proposals should be zero (safe-mode blocks deployment)
      (is (zero? (:proposals result)))
      (is (= :safe-mode (:degradation-mode result))))))

(deftest meta-loop-increments-cycle-count-test
  (testing "cycle count increments across calls"
    (let [stream (make-stream)
          ctx (meta-loop/create-meta-loop-context {:event-stream stream})
          r1 (meta-loop/run-meta-loop-cycle! ctx {} {})
          r2 (meta-loop/run-meta-loop-cycle! ctx {} {})
          r3 (meta-loop/run-meta-loop-cycle! ctx {} {})]
      (is (= 1 (:cycle-number r1)))
      (is (= 2 (:cycle-number r2)))
      (is (= 3 (:cycle-number r3))))))
