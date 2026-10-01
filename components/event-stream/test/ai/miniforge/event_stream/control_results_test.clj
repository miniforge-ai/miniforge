;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.control-results-test
  (:require [clojure.test :refer [deftest is]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.response.interface :as response]
            [slingshot.slingshot :refer [throw+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} failure-message "Intervention unavailable")

(defn- ^{:stratum 0} action []
  (events/create-control-action :pause
                                {:target-type :workflow :target-id (random-uuid)}
                                {:principal "operator" :role :operator :listener-id (random-uuid)}))

(defn- ^{:stratum 0} execute [action execution-fn & [opts]]
  (let [stream (events/create-event-stream {:sinks []})
        result (events/execute-control-action! stream action execution-fn opts)
        emitted (events/get-events stream {:event-type :control-action/executed})]
    {:result result :recorded (mapv :action/result emitted)}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} returned-response-failures-are-not-wrapped-as-success
  (let [failure (response/error failure-message)]
    (doseq [value [failure (response/failure failure-message)
                   (assoc failure :status :failed) (assoc failure :status :failure)
                   (assoc failure :status :denied)]]
      (let [{:keys [result recorded]} (execute (action) (constantly value))]
        (is (= value result))
        (is (= [value] recorded))
        (is (not (response/success? result)))))))

(deftest ^{:stratum 1} returned-anomalies-retain-provenance
  (doseq [value [(anomaly/anomaly :fault failure-message {:operation :pause})
                 (response/make-anomaly :anomalies/fault failure-message {:operation :pause})]]
    (let [{:keys [result recorded]} (execute (action) (constantly value))]
      (is (response/error? result))
      (is (= value (get-in result [:error :data])))
      (is (= [result] recorded)))))

(deftest ^{:stratum 1} successes-are-wrapped-only-once
  (doseq [output [{:paused true} nil false]]
    (let [success (response/success output)]
      (doseq [value [output success]]
        (let [{:keys [result recorded]} (execute (action) (constantly value))]
          (is (response/success? result))
          (is (= output (:output result)))
          (is (= [result] recorded)))))))

(deftest ^{:stratum 1} thrown-anomalies-retain-provenance
  (let [value (anomaly/anomaly :fault failure-message {:operation :pause})
        {:keys [result recorded]} (execute (action) (fn [_] (throw+ value)))]
    (is (response/error? result))
    (is (= value (get-in result [:error :data])))
    (is (= [result] recorded))))

(deftest ^{:stratum 1} exceptions-fail-and-fatal-errors-propagate
  (doseq [exception [(ex-info failure-message {:operation :pause})
                    (Exception. failure-message)]]
    (let [{:keys [result recorded]} (execute (action) (fn [_] (throw exception)))]
      (is (response/error? result))
      (is (= failure-message (get-in result [:error :message])))
      (is (= (or (ex-data exception) {}) (get-in result [:error :data])))
      (is (= [result] recorded))))
  (is (thrown? AssertionError (execute (action) (fn [_] (throw (AssertionError.)))))))

(deftest ^{:stratum 1} denied-actions-never-invoke-the-effect
  (let [calls (atom 0)
        denied-action (assoc-in (action) [:action/requester :role] :unknown)
        {:keys [result recorded]} (execute denied-action (fn [_] (swap! calls inc)))]
    (is (zero? @calls))
    (is (= :denied (:status result)))
    (is (= [result] recorded))))

(deftest ^{:stratum 1} malformed-actions-record-denial-without-invoking-the-effect
  (doseq [action-type [nil false "pause"]]
    (let [calls (atom 0)
          invalid (assoc (action) :action/type action-type)
          opts {:roles {:operator {:workflows #{action-type}}}}
          {:keys [result recorded]} (execute invalid (fn [_] (swap! calls inc)) opts)]
      (is (zero? @calls))
      (is (= :denied (:status result)))
      (is (= [result] recorded)))))

(deftest ^{:stratum 1} interruption-preserves-the-worker-signal
  (let [{:keys [result interrupted?]}
        @(future
           (try
             (assoc (execute (action) (fn [_] (throw (InterruptedException. failure-message))))
                    :interrupted? (.isInterrupted (Thread/currentThread)))
             (finally (Thread/interrupted))))]
    (is interrupted?)
    (is (response/error? result))))
