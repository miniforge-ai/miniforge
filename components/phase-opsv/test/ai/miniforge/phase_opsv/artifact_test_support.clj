;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifact-test-support
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.lifecycle :as lifecycle]
            [ai.miniforge.phase-opsv.test-support :as support]
            [clojure.java.io :as io]
            [clojure.test :refer [is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record-transform [calls _ctx]
  (swap! calls inc))

(defn- ^{:stratum 0} temporary-directory []
  (.getCanonicalFile (.toFile (Files/createTempDirectory "opsv-artifacts-" (make-array FileAttribute 0)))))

(defn- ^{:stratum 0} configured-context [root]
  (-> (support/execution-context (support/test-adapter support/ramp-steps))
      (update :execution/input dissoc :opsv/evidence-bundle-id :opsv/evidence-refs
              :opsv/metric-snapshot-artifact-refs :opsv/policy-diff-artifact-refs)
      (assoc-in [:execution/opts :opsv/artifact-directory] (.getPath root))
      (assoc :event-stream (events/create-event-stream {:sinks []}))))

(defn ^{:stratum 0} step [ctx [phase-key transform]]
  (let [interceptor (lifecycle/interceptor {} phase-key transform)
        completed ((:leave interceptor) ((:enter interceptor) ctx))]
    (is (= :success (get-in completed [:phase :result :status])))
    (assoc-in completed [:execution/phase-results phase-key :result] (get-in completed [:phase :result]))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} assert-blocked-transform [ctx]
  (let [calls (atom 0)
        interceptor (lifecycle/interceptor {} :opsv/actuate (partial record-transform calls))
        result ((:enter interceptor) ctx)]
    (is (= :error (get-in result [:phase :result :status])))
    (is (zero? @calls))))

(defn ^{:stratum 1} with-context [f]
  (let [root (temporary-directory)
        ctx (configured-context root)]
    (try (f ctx (.getPath root))
         (finally (doseq [file (reverse (file-seq root))] (io/delete-file file))))))

(comment
  (with-context (fn [_ctx directory] directory)))
