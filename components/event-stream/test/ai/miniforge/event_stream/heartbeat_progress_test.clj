;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-progress-test
  (:require [ai.miniforge.event-stream.heartbeat-progress :as progress]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} activation [sources]
  (progress/start {:workflow/id (random-uuid)
                   :workflow/phase :opsv/execute
                   :heartbeat/activation-id (random-uuid)}
                  (java.util.Date. 100) 0 sources))

(defn- ^{:stratum 0} observation [state position monotonic-ms]
  (merge (:identity state)
         {:progress/source :opsv/load-step
          :progress/sequence position
          :progress/monotonic-ms monotonic-ms
          :progress/observed-at (java.util.Date. 90)}))

(defn- ^{:stratum 0} snapshot [state monotonic-ms]
  (progress/payload (progress/tick state) (java.util.Date. 80) monotonic-ms 10))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} ticks-and-backward-wall-clock-never-report-progress
  (let [state (activation #{:opsv/load-step})
        ticking (nth (iterate progress/tick state) 3)
        payload (snapshot ticking 50)]
    (is (= 4 (:heartbeat/sequence payload)))
    (is (= 50 (:heartbeat/elapsed-ms payload)))
    (is (= :none (:phase/progress-status payload)))
    (is (= 0 (:phase/progress-count payload)))
    (is (= 50 (:phase/progress-gap-ms payload)))
    (is (not (contains? payload :phase/last-progress-at)))))

(deftest ^{:stratum 1} only-new-matching-substantive-progress-resets-gap
  (let [state (activation #{:opsv/load-step})
        event (observation state 1 20)
        advanced (progress/observe state event)
        payload (snapshot advanced 50)]
    (is (= :observed (:phase/progress-status payload)))
    (is (= 1 (:phase/progress-count payload)))
    (is (= 30 (:phase/progress-gap-ms payload)))
    (is (= (:progress/observed-at event) (:phase/last-progress-at payload)))
    (doseq [ignored [event (observation state 0 30) (observation state 2 10)
                     (assoc event :workflow/id (random-uuid))
                     (assoc event :workflow/phase :opsv/verify)
                     (assoc event :heartbeat/activation-id (random-uuid))
                     (assoc event :progress/source :workflow/phase-heartbeat)]]
      (is (= advanced (progress/observe advanced ignored))))))

(deftest ^{:stratum 1} unknown-coverage-does-not-invent-progress-or-zero-gap
  (let [state (activation :unknown)
        unchanged (progress/observe state (observation state 1 20))
        payload (snapshot unchanged 50)]
    (is (= state unchanged))
    (is (= :unknown (:phase/progress-status payload)))
    (doseq [field [:phase/progress-count :phase/progress-gap-ms :phase/last-progress-at]]
      (is (not (contains? payload field))))))

(deftest ^{:stratum 1} reentry-starts-a-fresh-activation-without-retained-progress
  (let [previous (activation #{:opsv/load-step})
        identity (assoc (:identity previous) :heartbeat/activation-id (random-uuid))
        fresh (progress/start identity (java.util.Date. 200) 100 #{:opsv/load-step})]
    (is (= fresh (progress/observe fresh (observation previous 1 20))))))

(deftest ^{:stratum 1} absent-source-instrumentation-is-explicitly-unknown
  (doseq [sources [nil #{} :unknown]]
    (is (= :unknown (:phase/progress-status (snapshot (activation sources) 50))))))

(comment ::deterministic-progress)
