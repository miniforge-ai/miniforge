;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-drift
  "Record correlated verification environment drift without authorizing a rerun."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.event-delivery :as delivery]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.runtime-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} mismatch [expected receipt]
  (let [deviation {:expected expected
                   :observed (:environment-fingerprint receipt)
                   :verification/id (:verification/id receipt)}
        drift {:opsv/signal :environment-fingerprint
               :opsv/deviation deviation
               :opsv/suggested-rerun? true}]
    (anomaly/anomaly :conflict (msg/ts :verification/environment-mismatch)
                     {:opsv/environment-drift drift})))

(defn- ^{:stratum 0} publish! [ctx stream drift]
  (let [portable (artifact/content-digest drift)
        bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])]
    (if (anomaly/anomaly? portable)
      portable
      (delivery/emit! ctx stream
                      (events/drift-detected stream (context/workflow-id ctx) bundle-id drift)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish-with-exception-handling! [ctx drift]
  ;; No slingshot dependency; preserve cancellation and fatal JVM outcomes.
  (try
    (when-let [stream (context/stream ctx)] (publish! ctx stream drift))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch Exception _
      (anomaly/anomaly :unavailable (msg/ts :event/publication-failed) {}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} record! [ctx result]
  (if-let [drift (get-in result [:anomaly/data :opsv/environment-drift])]
    (let [failure (publish-with-exception-handling! ctx drift)]
      (cond-> result
        failure (assoc-in [:anomaly/data :opsv/drift-publication-failure] failure)))
    result))

(comment
  (record! {} (mismatch {:cluster "before"} {:environment-fingerprint {:cluster "after"}})))
