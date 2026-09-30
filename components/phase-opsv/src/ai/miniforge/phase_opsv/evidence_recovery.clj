;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.evidence-recovery
  "Resume terminal evidence only, retaining the failed phase and its actual effects."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.evidence-checkpoint :as checkpoint]
            [ai.miniforge.phase-opsv.evidence-runtime :as runtime]
            [ai.miniforge.phase-opsv.finalization :as finalization]
            [ai.miniforge.phase-opsv.finalization-boundary :as boundary]
            [ai.miniforge.phase-opsv.finalization-config :as config]
            [ai.miniforge.phase-opsv.finalization-model :as model]
            [ai.miniforge.phase-opsv.lifecycle-result :as result]
            [ai.miniforge.phase-opsv.terminal-evidence :as terminal]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} recoverable? [ctx]
  (let [failure (get-in ctx [:phase :result :output])]
    (and (map? ctx) (map? (:execution/opts ctx)) (config/enabled? ctx)
         (= :opsv/actuate (get-in ctx [:phase :name]))
         (= :error (get-in ctx [:phase :result :status]))
         (anomaly/anomaly? failure)
         (map? (get-in failure [:anomaly/data :opsv/phase-output :opsv/phase-failure]))
         (uuid? (get-in ctx [:execution/input :opsv/evidence-bundle-id])))))

(defn- ^{:stratum 0} publish-finalized-result! [ctx failure]
  (let [published (boundary/publish-finalized! ctx)]
    (result/phase-result
     (if (anomaly/anomaly? published)
       (assoc-in failure [:anomaly/data :opsv/evidence-failure] published)
       (update-in failure [:anomaly/data :opsv/phase-output] merge published)))))

(defn- ^{:stratum 0} persist-result [ctx phase-result]
  (let [persisted (runtime/persist (assoc-in ctx [:phase :result] phase-result))]
    (if-let [failure (checkpoint/persistence-failure persisted)]
      (assoc-in failure [:anomaly/data :opsv/phase-output]
                (get-in phase-result [:output :anomaly/data :opsv/phase-output]))
      persisted)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} recover! [ctx]
  (let [restored (runtime/ensure-assembly ctx)
        failure (update (get-in ctx [:phase :result :output]) :anomaly/data dissoc :opsv/evidence-failure)]
    (if (anomaly/anomaly? restored)
      restored
      (let [phase-result (if (= :finalized (:opsv.assembly/status (finalization/assembly restored)))
                           (publish-finalized-result! restored failure)
                           (terminal/complete-failure! restored :opsv/actuate (result/phase-result failure)))]
        (persist-result restored phase-result)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} recover-with-exception-handling! [ctx]
  (let [restored (checkpoint/restore-terminal ctx)]
    (cond
      (anomaly/anomaly? restored) restored
      (recoverable? restored)
      (boundary/call-with-exception-handling
       (get-in restored [:phase :result :output :anomaly/data :opsv/phase-output]) #(recover! restored))
      :else (assoc (model/failure {} :invalid-recovery-context) :anomaly/type :invalid-input))))
