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

(defn ^{:stratum 0} with-context [f]
  (let [root (.getCanonicalFile (.toFile (Files/createTempDirectory "opsv-artifacts-"
                                                                 (make-array FileAttribute 0))))
        ctx (-> (support/execution-context (support/test-adapter support/ramp-steps))
                (update :execution/input dissoc :opsv/evidence-bundle-id :opsv/evidence-refs
                        :opsv/metric-snapshot-artifact-refs :opsv/policy-diff-artifact-refs)
                (assoc-in [:execution/opts :opsv/artifact-directory] (.getPath root))
                (assoc :event-stream (events/create-event-stream {:sinks []})))]
    (try (f ctx (.getPath root))
         (finally (doseq [file (reverse (file-seq root))] (io/delete-file file))))))

(defn ^{:stratum 0} step [ctx [phase-key transform]]
  (let [interceptor (lifecycle/interceptor {} phase-key transform)
        completed ((:leave interceptor) ((:enter interceptor) ctx))]
    (is (= :success (get-in completed [:phase :result :status])))
    (assoc-in completed [:execution/phase-results phase-key :result] (get-in completed [:phase :result]))))
