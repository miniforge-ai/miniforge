;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.phase-publication-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.runner-event-dispatch :as dispatch]
            [ai.miniforge.workflow.runner-events :as publishers]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-stream [verify]
  (fs/with-temp-dir [directory {:prefix "phase-publication-"}]
    (let [options {:journal-directory (str (fs/canonicalize directory))}
          stream (events/create-current-event-stream options)]
      (is (events/current-event-stream? stream))
      (try+ (verify stream options)
        (finally (events/close-current-event-stream! stream))))))

(defn- ^{:stratum 0} context [] {:execution/id (random-uuid)})

(defn- ^{:stratum 0} draft [stream]
  (events/phase-started stream (random-uuid) :plan {} {:phase/expected-agent :agent/none}))

(defn- ^{:stratum 0} unexpected [& _args]
  (throw (ex-info "publication interrupted" {:source :test-publication})))

(defn- ^{:stratum 0} interrupted [& _args]
  (throw (InterruptedException. "publication cancelled")))

(defn- ^{:stratum 0} reopened-events [options]
  (let [stream (events/create-current-event-stream options)]
    (try+ (events/get-events stream)
      (finally (events/close-current-event-stream! stream)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-receipt [stream options]
  (let [event (draft stream)
        receipt (dispatch/publish-event! stream event)]
    (is (= (:event/id event) (:event/id receipt)))
    (is (= 0 (:event/sequence-number receipt)))
    (events/close-current-event-stream! stream)
    (is (= [receipt] (reopened-events options)))))

(defn- ^{:stratum 1} verify-closed-journal [stream options]
  (let [event (draft stream)]
    (events/close-current-event-stream! stream)
    (is (anomaly/any-anomaly? (dispatch/publish-event! stream event)))
    (is (empty? (reopened-events options)))))

(defn- ^{:stratum 1} verify-construction-and-transport-failures [stream _options]
  (let [failure (anomaly/anomaly :invalid-input "invalid phase draft" {})
        event (draft stream)]
    (is (= failure (dispatch/publish-event! stream failure)))
    (with-redefs [events/publish! unexpected]
      (is (anomaly/any-anomaly? (dispatch/publish-event! stream event))))
    (with-redefs [events/phase-started unexpected events/phase-completed unexpected]
      (doseq [result [(publishers/publish-phase-started! stream (context) :plan)
                     (publishers/publish-phase-completed! stream (context) :plan {})]]
        (is (anomaly/any-anomaly? result))
        (is (= {:source :test-publication} (:anomaly/ex-data result)))))
    (is (empty? (events/get-events stream)))))

(defn- ^{:stratum 1} verify-interruption [stream _options]
  (let [event (draft stream)]
    (try+
      (with-redefs [events/publish! interrupted]
        (let [result (dispatch/publish-event! stream event)
              ;; clojure.test's STM report counter clears thread interruption.
              interrupted? (.isInterrupted (Thread/currentThread))]
          (is (anomaly/any-anomaly? result))
          (is interrupted?)))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 1} legacy-transport-failures-remain-warning-only
  (let [stream (events/create-event-stream {:sinks []})
        result (atom :not-called)]
    (with-redefs [events/publish! unexpected]
      (is (seq (with-out-str (reset! result (dispatch/publish-event! stream {})))))
      (is (nil? @result)))
    (is (nil? (dispatch/publish-event! nil {})))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} current-receipt-is-durable (with-stream verify-receipt))

(deftest ^{:stratum 2} closed-journal-does-not-become-observer-success
  (with-stream verify-closed-journal))

(deftest ^{:stratum 2} current-publication-failures-reach-the-caller
  (with-stream verify-construction-and-transport-failures))

(deftest ^{:stratum 2} current-publication-preserves-interruption
  (with-stream verify-interruption))
