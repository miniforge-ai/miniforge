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

(ns ai.miniforge.agent.meta.loop-test
  "Integration test for the meta-agent loop.

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
  "Create a fully wired meta-loop context."
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

(deftest meta-loop-context-records-workflow-outcomes-test
  (testing "context metrics store accumulates workflow outcomes and failures"
    (let [ctx (meta-loop/create-meta-loop-context (make-stream))]
      (meta-loop/record-workflow-outcome! ctx #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" :completed)
      (meta-loop/record-workflow-outcome! ctx
                                          #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                                          :failed
                                          :failure.class/timeout)
      (let [metrics-store @(:metrics-store ctx)]
        (is (= 2 (count (:workflow-metrics metrics-store))))
        (is (= 1 (count (:failure-events metrics-store))))))))

(deftest meta-loop-context-cycle-test
  (testing "context-backed cycle returns the public namespaced learning result"
    (let [stream (make-stream)
          ctx (meta-loop/create-meta-loop-context stream
                                                  {:reliability {:windows [:7d]
                                                                 :tiers [:standard]}})
          _ (meta-loop/record-workflow-outcome! ctx
                                                #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
                                                :failed
                                                :failure.class/timeout)
          result (meta-loop/run-cycle-from-context! ctx)]
      (is (contains? result :cycle/started-at))
      (is (contains? result :cycle/duration-ms))
      (is (contains? result :cycle/signal-count))
      (is (contains? result :cycle/diagnosis-count))
      (is (contains? result :cycle/degradation-mode)))))
