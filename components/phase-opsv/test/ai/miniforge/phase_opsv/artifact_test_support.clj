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

(defn ^{:stratum 0} configured [ctx]
  (assoc-in ctx [:execution/opts :opsv/evidence-base]
            {:evidence-bundle/workflow-id (:execution/id ctx)
             :evidence-bundle/created-at #inst "2026-09-28T10:00:00Z"
             :evidence-bundle/version "1.0.0"
             :evidence/intent {:intent/type :update
                               :intent/description "Evaluate catalog scaling."
                               :intent/business-reason "Meet the declared latency objective."
                               :intent/constraints []
                               :intent/declared-at #inst "2026-09-28T10:00:00Z"}}))

(defn ^{:stratum 0} run-last-phase [ctx]
  (let [[phase-key transform] (last support/handlers)
        interceptor (lifecycle/interceptor {} phase-key transform)]
    ((:leave interceptor) ((:enter interceptor) ctx))))

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

(defn ^{:stratum 1} with-context [f]
  (let [root (temporary-directory)
        ctx (configured-context root)]
    (try (f ctx (.getPath root))
         (finally (doseq [file (reverse (file-seq root))] (io/delete-file file))))))

(comment
  (with-context (fn [_ctx directory] directory)))
