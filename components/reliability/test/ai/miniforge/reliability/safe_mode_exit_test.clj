;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.reliability.safe-mode-exit-test
  (:require [ai.miniforge.event-stream.interface.stream :as events]
            [ai.miniforge.reliability.interface :as reliability]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} pause-exit-event [publish entered release stream event]
  (when (= :safe-mode/exited (:event/type event))
    (deliver entered true)
    @release)
  (publish stream event))

(defn- ^{:stratum 0} exit! [manager]
  (reliability/exit-safe-mode! manager "Incident resolved" "operator"))

(defn- ^{:stratum 0} reenter! [manager]
  (reliability/enter-safe-mode! manager :manual "new incident"))

(defn- ^{:stratum 0} evaluate-exhausted! [manager]
  (reliability/evaluate-degradation! manager
    {[:latency :critical :7d] {:error-budget/tier :critical :error-budget/remaining 0.0}}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} entry-decisions-wait-for-concurrent-exit-test
  (doseq [operation [reenter! evaluate-exhausted!]]
    (let [manager (reliability/create-degradation-manager nil)
          state (:fsm-state manager)
          started (promise)]
      (reenter! manager)
      (let [request (locking state
                      (let [pending (future (deliver started true) (operation manager))]
                        (is (= true (deref started 2000 :timeout)))
                        (is (= :blocked (deref pending 100 :blocked)))
                        (exit! manager)
                        pending))]
        (try
          (is (= :safe-mode (deref request 2000 :timeout)))
          (is (= :safe-mode (reliability/degradation-mode manager)))
          (finally (future-cancel request)))))))

(defn- ^{:stratum 1} assert-concurrent-operation [manager entered release operation]
  (let [exiting (future (exit! manager))]
    (try
      (is (= true (deref entered 2000 :timeout)))
      (let [started (promise)
            concurrent (future (deliver started true) (operation manager))]
        (try
          (is (= true (deref started 2000 :timeout)))
          (is (= :blocked (deref concurrent 100 :blocked)))
          (deliver release true)
          (is (= :nominal (deref exiting 2000 :timeout)))
          (is (not= :timeout (deref concurrent 2000 :timeout)))
          (finally (deliver release true) (future-cancel concurrent))))
      (finally (deliver release true) (future-cancel exiting)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} exit-publication-is-serialized-with-exit-and-reentry-test
  (doseq [operation [exit! reenter!]]
    (let [stream (events/create-event-stream {:sinks []})
          manager (reliability/create-degradation-manager stream)
          entered (promise)
          release (promise)
          publish (partial pause-exit-event events/publish! entered release)]
      (reliability/enter-safe-mode! manager :manual "first incident")
      (with-redefs [events/publish! publish]
        (assert-concurrent-operation manager entered release operation))
      (let [types (mapv :event/type (events/get-events stream))]
        (is (= 1 (count (filter #{:safe-mode/exited} types))))
        (when (= operation reenter!)
          (is (= :safe-mode/entered (last types))))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.reliability.safe-mode-exit-test))
