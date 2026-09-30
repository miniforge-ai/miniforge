;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.reliability.safe-mode-boundary-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.reliability.interface :as reliability]
            [ai.miniforge.event-stream.interface.stream :as events]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} stop-precedes-mode-change-and-event-publication-test
  (let [observed (atom []) holder (atom nil)
        manager (reliability/create-degradation-manager (events/create-event-stream {:sinks []})
                  {:before-safe-mode! (fn [_]
                                        (swap! observed conj (reliability/degradation-mode @holder))
                                        {:stopped? true})})]
    (reset! holder manager)
    (with-redefs [events/publish! (fn [& _] (swap! observed conj (reliability/degradation-mode manager)) nil)]
      (is (= :safe-mode (reliability/enter-safe-mode! manager :emergency-stop "stop"))))
    (is (= [:nominal :safe-mode :safe-mode] @observed))
    (is (= {:stopped? true} (reliability/safe-mode-stop-result manager)))))

(deftest ^{:stratum 0} failing-host-hook-is-retained-and-does-not-skip-safe-mode-test
  (let [manager (reliability/create-degradation-manager nil
                  {:before-safe-mode! (fn [_] (throw (AssertionError. "cleanup failed")))})]
    (is (= :safe-mode (reliability/enter-safe-mode! manager :manual "stop")))
    (is (= :fatal (:anomaly/type (reliability/safe-mode-stop-result manager))))))

(deftest ^{:stratum 0} interrupting-hook-preserves-interruption-test
  (let [manager (reliability/create-degradation-manager nil
                  {:before-safe-mode! (fn [_] (throw (InterruptedException.)))})]
    (reliability/enter-safe-mode! manager :manual "stop")
    (is (Thread/interrupted))
    (is (= :safe-mode (reliability/degradation-mode manager)))
    (is (anomaly/anomaly? (reliability/safe-mode-stop-result manager)))))

(deftest ^{:stratum 0} invalid-hooks-and-empty-exit-authority-are-refused-test
  (doseq [config [42 {:before-safe-mode! nil} {:before-safe-mode! :not-a-function}]]
    (is (= :invalid-input (:anomaly/type (reliability/create-degradation-manager nil config)))))
  (let [manager (reliability/create-degradation-manager nil)]
    (reliability/enter-safe-mode! manager :manual "stop")
    (doseq [[reason principal] [["" "operator"] ["reason" " "] [nil "operator"]]]
      (is (= :invalid-input (:anomaly/type (reliability/exit-safe-mode! manager reason principal)))))
    (is (= :safe-mode (reliability/degradation-mode manager)))))

(deftest ^{:stratum 0} budget-exhaustion-invokes-the-same-stop-hook-test
  (let [signals (atom [])
        manager (reliability/create-degradation-manager nil
                  {:before-safe-mode! #(do (swap! signals conj %) {:stopped? true})})]
    (reliability/evaluate-degradation! manager
      {[:SLI-1 :critical :7d] {:error-budget/tier :critical :error-budget/remaining 0.0}})
    (is (= :safe-mode (reliability/degradation-mode manager)))
    (is (= [:error-budget] (mapv :safe-mode-trigger @signals)))))

(deftest ^{:stratum 0} concurrent-entry-stops-once-and-does-not-lose-safe-mode-test
  (let [entered (promise) release (promise) calls (atom 0)
        manager (reliability/create-degradation-manager nil
                  {:before-safe-mode! (fn [_] (swap! calls inc) (deliver entered true) @release)})
        first-entry (future (reliability/enter-safe-mode! manager :manual "first"))]
    (try
      (is (= true (deref entered 2000 :timeout)))
      (let [second-entry (future (reliability/enter-safe-mode! manager :emergency-stop "second"))]
        (deliver release {:stopped? true})
        (is (= :safe-mode (deref first-entry 2000 :timeout)))
        (is (= :safe-mode (deref second-entry 2000 :timeout)))
        (is (= 1 @calls)))
      (finally (deliver release {:stopped? true}) (future-cancel first-entry)))))
