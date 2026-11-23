;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.snapshot-failure-publication-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.runner-checkpoint :as checkpoint]
            [ai.miniforge.workflow.runner-event-dispatch :as dispatch]
            [ai.miniforge.workflow.snapshot-failure-test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refuse-publication [_stream _event]
  (anomaly/anomaly :unavailable "publication refused" {}))

(defn- ^{:stratum 0} nonfatal-publication [_stream _event]
  (throw (ex-info "publication failed" {})))

(defn- ^{:stratum 0} fatal-publication [_stream _event]
  (throw (AssertionError. "fatal publication failure")))

(defn- ^{:stratum 0} verify-stream-compatibility [root]
  (let [[logger entries] (log/collecting-logger {})
        failure (support/resource-failure)
        legacy (events/create-event-stream {:sinks []})]
    (doseq [stream [nil {:websocket :unopened} legacy]]
      (is (identical? failure (checkpoint/report! (support/pending-context root stream logger) failure))))
    (is (= 3 (support/warning-count entries)))
    (is (= [:workflow/machine-snapshot-write-failed] (mapv :event/type (events/get-events legacy))))
    (is (= :failure.class/resource (:failure/class (first (events/get-events legacy)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-publication-failures [root stream]
  (let [[logger entries] (log/collecting-logger {})
        ctx (support/pending-context root stream logger)
        failure (support/resource-failure)]
    (doseq [publish [refuse-publication nonfatal-publication]]
      (reset! entries [])
      (with-redefs [dispatch/publish-event! publish]
        (is (identical? failure (checkpoint/report! ctx failure))))
      (is (= 2 (support/warning-count entries))))
    (with-redefs [dispatch/publish-event! fatal-publication]
      (is (thrown? AssertionError (checkpoint/report! ctx failure))))
    (is (empty? (events/get-events stream)))))

(deftest ^{:stratum 1} absent-observer-and-legacy-streams-preserve-warning-only-compatibility
  (fixture/call-with-temp-checkpoint-root verify-stream-compatibility))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} failed-failure-publication-keeps-the-write-anomaly-but-never-swallows-fatal-errors
  (fixture/call-with-temp-checkpoint-root (partial support/with-journal verify-publication-failures)))

(comment ::snapshot-publication-failure-semantics)
