;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-heartbeats-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.runner-heartbeats :as heartbeats]
            [ai.miniforge.workflow.runner-events :as events]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refusal []
  (anomaly/anomaly :unavailable "fixture heartbeat unavailable" {}))

(defn- ^{:stratum 0} handles [] (atom (array-map :execute :second :plan :first)))

(defn- ^{:stratum 0} stop-recording [calls failure handle]
  (swap! calls conj handle)
  (when (= :first handle) (throw failure)))

(deftest ^{:stratum 0} reentry-releases-previous-handle-before-replacement
  (let [owned (atom {:plan :old}) stopped (atom [])]
    (with-redefs [events/start-phase-heartbeat! (constantly :new)
                  events/stop-phase-heartbeat! (partial swap! stopped conj)]
      (is (nil? (heartbeats/start! nil owned {} :plan {})))
      (is (= [:old] @stopped))
      (is (= {:plan :new} @owned)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} refused-start-does-not-become-a-handle
  (let [owned (atom {}) failure (refusal)]
    (with-redefs [events/start-phase-heartbeat! (constantly failure)]
      (is (identical? failure (heartbeats/start! nil owned {} :plan {})))
      (is (empty? @owned)))))

(deftest ^{:stratum 1} refused-stop-retains-ownership-and-prevents-replacement
  (let [owned (atom {:plan :old}) failure (refusal) acquired (atom [])]
    (with-redefs [events/start-phase-heartbeat! (partial swap! acquired conj)
                  events/stop-phase-heartbeat! (constantly failure)]
      (is (identical? failure (heartbeats/start! nil owned {} :plan {})))
      (is (= {:plan :old} @owned))
      (is (empty? @acquired)))))

(deftest ^{:stratum 1} fatal-stop-still-attempts-all-handles-and-is-retryable
  (let [owned (handles) calls (atom []) failure (Error. "fixture fatal stop")]
    (with-redefs [events/stop-phase-heartbeat! (partial stop-recording calls failure)]
      (try+ (heartbeats/stop-all! owned)
        (is false "fatal failure must propagate")
        (catch Error caught (is (identical? failure caught)))))
    (is (= [:first :second] @calls))
    (is (= {:plan :first} @owned))
    (with-redefs [events/stop-phase-heartbeat! (constantly nil)]
      (is (nil? (heartbeats/stop-all! owned))))
    (is (empty? @owned))))

(deftest ^{:stratum 1} returned-stop-refusals-do-not-skip-remaining-handles
  (let [owned (handles) failure (refusal) calls (atom [])]
    (with-redefs [events/stop-phase-heartbeat! (comp (constantly failure) (partial swap! calls conj))]
      (is (identical? failure (heartbeats/stop-all! owned))))
    (is (= #{:first :second} (set @calls)))
    (is (= 2 (count @owned)))))

(comment ::runner-heartbeat-ownership-regressions)
