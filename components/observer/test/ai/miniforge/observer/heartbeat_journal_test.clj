;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.heartbeat-journal-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.observer.interface :as observer]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} payload [activation sequence status gap]
  (let [elapsed (+ 20 gap)]
    (cond-> {:phase/active-since (java.util.Date. 0)
             :heartbeat/activation-id activation :heartbeat/sequence sequence
             :heartbeat/observed-at (java.util.Date. elapsed)
             :heartbeat/interval-ms 10 :heartbeat/elapsed-ms elapsed
             :phase/progress-status status}
      (= :observed status) (assoc :phase/progress-count 1
                                 :phase/last-progress-at (java.util.Date. 20)
                                 :phase/progress-gap-ms gap))))

(defn- ^{:stratum 0} with-stream [operation]
  (fs/with-temp-dir [directory {:prefix "observer-heartbeat-journal-"}]
    (let [stream (events/create-current-event-stream {:journal-directory (str (fs/real-path directory))})]
      (is (not (anomaly/anomaly? stream)))
      (when-not (anomaly/anomaly? stream)
        (try+ (operation stream)
          (finally (events/close-current-event-stream! stream)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-journal [stream]
  (let [state (observer/create-alert-state)
        rules [{:alert/id :fixture/stall :alert/type :phase-gap :alert/threshold {:threshold-ms 10}}]
        workflow (random-uuid)
        activation (random-uuid)]
    (observer/start-alert-subscriber! stream workflow rules {:alert-state state})
    (doseq [[sequence status gap] [[1 :observed 11] [2 :observed 21] [3 :unknown 31]]]
      (let [draft (events/phase-heartbeat stream workflow :opsv/execute
                                          (payload activation sequence status gap))
            receipt (events/publish! stream draft)]
        (is (not (anomaly/anomaly? receipt)) (pr-str receipt))
        (when (= :observed status)
          (is (= gap (get-in @state [:alerts/latest :fixture/stall :alert/data :gap-ms]))))))
    (is (= 21 (get-in @state [:alerts/latest :fixture/stall :alert/data :gap-ms])))
    (is (= :unknown-coverage (get-in @state [:heartbeat/progress :progress/reason])))
    (is (= 2 (get-in @state [:heartbeat/position :event/sequence-number])))
    (is (= 3 (count (events/get-events stream))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} canonical-heartbeats-preserve-stalls-and-unknown-coverage
  (with-stream verify-journal))

(comment ::journal-to-observer-acceptance)
