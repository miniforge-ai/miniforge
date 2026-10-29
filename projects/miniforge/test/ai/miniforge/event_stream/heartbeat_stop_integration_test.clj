;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-stop-integration-test
  (:require [ai.miniforge.event-stream.heartbeat-stop-fixtures :as fixture]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.workflow-draft-fixtures :as journal]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-stopped [handle signals stopping]
  (is (nil? (fixture/stopped stopping)))
  (is (fixture/await! (:interrupted signals)))
  (is (.isShutdown ^java.util.concurrent.ScheduledExecutorService (:heartbeat/executor handle)))
  (is (.isCancelled ^java.util.concurrent.ScheduledFuture (:heartbeat/future handle))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-interrupting-stop [stream _]
  (let [signals (fixture/signals)]
    (events/subscribe! stream :fixture/blocked-listener (partial fixture/block! signals))
    (let [handle (events/start-heartbeat! stream (random-uuid) :opsv/execute {:interval-ms 1})]
      (try+
        (is (fixture/await! (:entered signals)))
        (let [stopping (future (events/stop-heartbeat! handle {:may-interrupt? true}))]
          (try+
            (verify-stopped handle signals stopping)
            (finally (fixture/release! signals) (fixture/stopped stopping))))
        (finally (fixture/release! signals) (events/stop-heartbeat! handle {:may-interrupt? true}))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} interrupting-stop-unblocks-a-tick-before-awaiting-its-fence
  (journal/with-stream verify-interrupting-stop))

(comment ::interrupting-stop-acceptance)
