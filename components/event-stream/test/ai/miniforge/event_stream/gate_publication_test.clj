;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.gate-publication-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.publication-store :as store]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} commit-after-failure! [attempts commit! scope event]
  (if (= 1 (swap! attempts inc))
    (anomaly/anomaly :unavailable "fixture storage failure" {})
    (commit! scope event)))

(defn- ^{:stratum 0} draft [stream]
  (assoc (events/create-event-draft stream :gate/rule-applied (random-uuid) "fixture rule")
         :scope/type :workflow :gate/phase :implement :rule/id :fixture/rule :rule/status :passed))

(defn- ^{:stratum 0} stream-with-store [ports]
  (with-redefs [store/durable-store (constantly ports)]
    (events/create-current-event-stream {:journal-directory "fixture"})))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} failed-gate-evidence-is-not-delivered-or-sequenced
  (let [storage (store/volatile-store)
        attempts (atom 0)
        observed (atom [])
        ports (assoc storage :commit! (partial commit-after-failure! attempts (:commit! storage)))
        stream (stream-with-store ports)
        event (draft stream)]
    (events/subscribe! stream :fixture (partial swap! observed conj))
    (is (anomaly/anomaly? (events/publish! stream event)))
    (is (empty? (events/get-events stream)))
    (is (empty? @observed))
    (let [receipt (events/publish! stream event)]
      (is (= 0 (:event/sequence-number receipt)))
      (is (= [receipt] @observed))
      (is (= receipt (events/publish! stream event)))
      (is (= [receipt] @observed)))
    (events/close-current-event-stream! stream)))

(comment
  (clojure.test/run-tests 'ai.miniforge.event-stream.gate-publication-test))
