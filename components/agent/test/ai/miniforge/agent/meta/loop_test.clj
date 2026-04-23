;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.agent.meta.loop-test
  "Integration test for the learning loop.

   Tests the full closed-loop cycle: reliability → diagnosis → improvement.

   Layer 0: Test factories
   Layer 1: Tests"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.meta.loop :as meta-loop]
   [ai.miniforge.reliability.interface :as rel]
   [ai.miniforge.improvement.interface :as imp]
   [ai.miniforge.event-stream.interface.stream :as stream]))

;------------------------------------------------------------------------------ Layer 0
;; Test factories

(defn- make-stream []
  (stream/create-event-stream {:sinks []}))

(defn- make-timestamp [] (java.util.Date.))

(defn- make-workflow-metrics
  "Create workflow metrics with the given statuses."
  [statuses]
  (let [ts (make-timestamp)]
    (mapv (fn [s] {:status s :timestamp ts}) statuses)))

(defn- make-failure-events
  "Create n failure events of the given class."
  [failure-class n]
  (let [ts (make-timestamp)]
    (vec (repeat n {:failure/class failure-class :timestamp ts}))))

(defn- make-phase-metrics
  "Create phase metrics with given iterations."
  [phase iterations-list]
  (let [ts (make-timestamp)]
    (mapv (fn [iters] {:phase phase :iterations iters :timestamp ts
                       :success? (<= iters 3)})
          iterations-list)))

(defn- make-full-context
  "Create a fully wired learning-loop context."
  [stream]
  (meta-loop/create-meta-loop-context
   {:reliability-engine (rel/create-engine stream {:windows [:7d] :tiers [:standard]})
    :degradation-manager (rel/create-degradation-manager stream)
    :improvement-pipeline (imp/create-pipeline)
    :event-stream stream}))

;------------------------------------------------------------------------------ Layer 1
;; Tests

(deftest meta-loop-cycle-test
  (testing "full meta-loop cycle with unhealthy metrics"
    (let [stream (make-stream)
          ctx (make-full-context stream)
          metrics {:workflow-metrics (make-workflow-metrics [:completed :failed :failed :completed])}
          result (meta-loop/run-meta-loop-cycle!
                  ctx metrics
                  {:failure-events (make-failure-events :failure.class/timeout 5)
                   :phase-metrics (make-phase-metrics :implement [5 4])})]

      (is (= 1 (:cycle-number result)))
      (is (map? (:reliability result)))
      (is (keyword? (:degradation-mode result)))
      (is (pos? (:signals result)))
      (is (pos? (:diagnoses result)))
      (is (pos? (:proposals result)))
      (is (pos? (count (imp/get-proposals (:improvement-pipeline ctx)))))
      (is (pos? (count (:events @stream)))))))

(deftest meta-loop-healthy-cycle-test
  (testing "healthy metrics produce fewer signals than unhealthy"
    (let [stream (make-stream)
          ctx (make-full-context stream)
          ts (make-timestamp)
          healthy-metrics {:workflow-metrics (make-workflow-metrics (repeat 10 :completed))
                           :gate-metrics (vec (repeat 10 {:passed? true :timestamp ts}))
                           :tool-metrics (vec (repeat 10 {:success? true :timestamp ts}))}
          healthy-result (meta-loop/run-meta-loop-cycle!
                          ctx healthy-metrics
                          {:failure-events []
                           :phase-metrics (make-phase-metrics :implement (repeat 10 1))})

          unhealthy-stream (make-stream)
          unhealthy-ctx (make-full-context unhealthy-stream)
          unhealthy-result (meta-loop/run-meta-loop-cycle!
                            unhealthy-ctx
                            {:workflow-metrics (make-workflow-metrics [:failed :failed])}
                            {:failure-events (make-failure-events :failure.class/timeout 5)})]

      (is (<= (:signals healthy-result) (:signals unhealthy-result))))))

(deftest meta-loop-safe-mode-skips-proposals-test
  (testing "safe-mode skips proposal generation"
    (let [stream (make-stream)
          deg-mgr (rel/create-degradation-manager stream)
          _ (rel/enter-safe-mode! deg-mgr :emergency-stop "Test")
          ctx (meta-loop/create-meta-loop-context
               {:reliability-engine (rel/create-engine stream {:windows [:7d] :tiers [:critical]})
                :degradation-manager deg-mgr
                :improvement-pipeline (imp/create-pipeline)
                :event-stream stream})
          result (meta-loop/run-meta-loop-cycle!
                  ctx
                  {:workflow-metrics (make-workflow-metrics [:failed :failed])}
                  {:failure-events (make-failure-events :failure.class/timeout 5)})]

      (is (pos? (:signals result)))
      (is (zero? (:proposals result)))
      (is (= :safe-mode (:degradation-mode result))))))

(deftest meta-loop-increments-cycle-count-test
  (testing "cycle count increments across calls"
    (let [ctx (meta-loop/create-meta-loop-context {:event-stream (make-stream)})
          r1 (meta-loop/run-meta-loop-cycle! ctx {} {})
          r2 (meta-loop/run-meta-loop-cycle! ctx {} {})
          r3 (meta-loop/run-meta-loop-cycle! ctx {} {})]
      (is (= 1 (:cycle-number r1)))
      (is (= 2 (:cycle-number r2)))
      (is (= 3 (:cycle-number r3))))))
