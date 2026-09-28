;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.control-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} stopped-fence-never-admits-or-reopens-test
  (let [fence (actuation/create-mutation-fence)
        calls (atom 0)]
    (is (= :done (actuation/at-mutation-boundary! fence (constantly :done))))
    (is (= {:stopped? true :in-flight 0} (actuation/stop-mutations! fence)))
    (dotimes [_ 2]
      (is (anomaly/anomaly? (actuation/at-mutation-boundary! fence #(swap! calls inc))))
      (is (= {:stopped? true :in-flight 0} (actuation/stop-mutations! fence))))
    (is (zero? @calls))))

(deftest ^{:stratum 0} stop-distinguishes-in-flight-from-new-effects-test
  (let [fence (actuation/create-mutation-fence)
        entered (promise)
        settle (promise)
        running (future (actuation/at-mutation-boundary!
                         fence #(do (deliver entered true) (deref settle 5000 :timeout))))]
    (try
      (is (= true (deref entered 5000 :timeout)))
      (is (= {:stopped? true :in-flight 1} (actuation/stop-mutations! fence)))
      (is (anomaly/anomaly? (actuation/at-mutation-boundary! fence (constantly :not-run))))
      (deliver settle :settled)
      (is (= :settled (deref running 5000 :timeout)))
      (is (= {:stopped? true :in-flight 0} (actuation/mutation-status fence)))
      (finally (deliver settle :cleanup) (future-cancel running)))))

(deftest ^{:stratum 0} callback-failure-does-not-leak-admission-test
  (let [fence (actuation/create-mutation-fence)]
    (is (anomaly/anomaly?
         (actuation/at-mutation-boundary! fence #(throw (ex-info "test failure" {})))))
    (is (= {:stopped? false :in-flight 0} (actuation/mutation-status fence)))))

(deftest ^{:stratum 0} invalid-runtime-handles-do-not-invoke-the-operation-test
  (let [calls (atom 0)]
    (doseq [fence [nil {} (atom {}) (Object.) {:state (atom {:stopped? false})}]]
      (is (anomaly/anomaly? (actuation/at-mutation-boundary! fence #(swap! calls inc))))
      (is (anomaly/anomaly? (actuation/stop-mutations! fence))))
    (is (zero? @calls))))

(deftest ^{:stratum 0} handles-do-not-expose-mutable-state-test
  (let [fence (actuation/create-mutation-fence)]
    (is (not (associative? fence)))
    (is (nil? (get fence :state)))
    (actuation/stop-mutations! fence)
    (is (false? (:stopped? (assoc (actuation/mutation-status fence) :stopped? false))))
    (is (true? (:stopped? (actuation/mutation-status fence))))))

(deftest ^{:stratum 0} interruption-preserves-thread-status-and-releases-admission-test
  (let [fence (actuation/create-mutation-fence)]
    (try
      (let [result (actuation/at-mutation-boundary! fence #(throw (InterruptedException.)))
            interrupted? (.isInterrupted (Thread/currentThread))]
        (is (anomaly/anomaly? result))
        (is interrupted?))
      (is (= {:stopped? false :in-flight 0} (actuation/mutation-status fence)))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 0} coordinated-stop-and-admission-race-test
  (dotimes [_ 10]
    (let [fence (actuation/create-mutation-fence)
          ready (CountDownLatch. 9)
          start (promise)
          calls (atom 0)
          run #(future (.countDown ready) (deref start 5000 nil) (%))
          workers (mapv (fn [_] (run #(actuation/at-mutation-boundary! fence
                                       (fn [] (swap! calls inc) :admitted)))) (range 8))
          stopper (run #(actuation/stop-mutations! fence))]
      (try
        (is (.await ready 5 TimeUnit/SECONDS))
        (deliver start true)
        (is (true? (:stopped? (deref stopper 5000 nil))))
        (let [results (mapv #(deref % 5000 :timeout) workers)
              admitted (count (filter #{:admitted} results))]
          (is (not-any? #{:timeout} results))
          (is (= admitted @calls))
          (is (anomaly/anomaly? (actuation/at-mutation-boundary! fence #(swap! calls inc))))
          (is (= admitted @calls))
          (is (= {:stopped? true :in-flight 0} (actuation/mutation-status fence))))
        (finally
          (deliver start true)
          (doseq [worker (conj workers stopper)] (future-cancel worker)))))))

(comment
  (actuation/mutation-status (actuation/create-mutation-fence)))
