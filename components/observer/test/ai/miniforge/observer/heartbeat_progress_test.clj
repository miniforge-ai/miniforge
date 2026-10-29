;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.heartbeat-progress-test
  (:require [ai.miniforge.observer.interface :as observer]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} heartbeat [status gap]
  {:event/type :workflow/phase-heartbeat :event/version "2.0.0"
   :phase/progress-status status :phase/progress-gap-ms gap})

(defn- ^{:stratum 0} rule []
  {:alert/id :fixture/stall :alert/type :phase-gap :alert/threshold {:threshold-ms 10}})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} current-stalls-use-progress-not-liveness
  (doseq [status [:none :observed]]
    (let [event (assoc (heartbeat status 11) :heartbeat/elapsed-ms 100
                       :phase/gap-since-last-event-ms 0)
          progress (observer/heartbeat-progress event)
          alerts (observer/evaluate-rules [(rule)] event (observer/create-alert-state))]
      (is (= :substantive (:progress/semantics progress)))
      (is (= 11 (:progress/gap-ms progress)))
      (is (= 11 (get-in (first alerts) [:alert/data :gap-ms]))))))

(deftest ^{:stratum 1} unknown-and-unsupported-are-explicitly-unavailable
  (doseq [[event reason] [[(heartbeat :unknown 100) :unknown-coverage]
                          [(assoc (heartbeat :observed 100) :event/version "3.0.0") :unsupported-version]
                          [(assoc (heartbeat :observed 100) :event/version "bad") :unsupported-version]
                          [(heartbeat :observed nil) :invalid-progress]
                          [(heartbeat :none -1) :invalid-progress]]]
    (is (= {:progress/status :unavailable :progress/reason reason}
           (observer/heartbeat-progress event)))
    (is (empty? (observer/evaluate-rules [(rule)] event (observer/create-alert-state))))))

(deftest ^{:stratum 1} legacy-gap-is-explicitly-liveness
  (doseq [version [nil "1.0.0"]]
    (let [event {:event/type :workflow/phase-heartbeat :event/version version
                 :phase/gap-since-last-event-ms 11}]
      (is (= :legacy-liveness (:progress/semantics (observer/heartbeat-progress event))))
      (is (= 1 (count (observer/evaluate-rules [(rule)] event (observer/create-alert-state))))))))

(deftest ^{:stratum 1} unsupported-version-is-recorded-and-position-advances
  (let [state (observer/create-alert-state)
        skipped (assoc (heartbeat :observed 20) :event/version "3.0.0" :event/sequence-number 3)
        current (assoc (heartbeat :unknown nil) :event/sequence-number 4)]
    (observer/evaluate-rules [(rule)] skipped state)
    (is (= 3 (get-in @state [:heartbeat/last-skipped :event/sequence-number])))
    (is (= 3 (get-in @state [:heartbeat/position :event/sequence-number])))
    (observer/evaluate-rules [(rule)] current state)
    (is (= 4 (get-in @state [:heartbeat/position :event/sequence-number])))
    (is (= :unknown-coverage (get-in @state [:heartbeat/progress :progress/reason])))
    (is (= 3 (get-in @state [:heartbeat/last-skipped :event/sequence-number])))))

(comment ::heartbeat-observer-semantics)
