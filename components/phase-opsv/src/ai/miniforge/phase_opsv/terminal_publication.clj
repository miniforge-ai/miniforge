;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.terminal-publication
  "Publish retained terminal material only after confirming its effect dispositions."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.artifact-boundary :as artifacts]
            [ai.miniforge.phase-opsv.events :as events]
            [ai.miniforge.phase-opsv.finalization-boundary :as finalization]
            [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.pr-audit :as audit]
            [ai.miniforge.phase-opsv.terminal-model :as model]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} finalize-after-events! [ctx output]
  (let [publication (events/emit-phase-events! ctx :opsv/actuate output)]
    (if (anomaly/any-anomaly? publication) publication (finalization/finalize! ctx output))))

(defn- ^{:stratum 0} confirm-disposition! [ctx retained transaction]
  (let [recorded (when-not (anomaly/any-anomaly? retained) (audit/record! ctx transaction))]
    (cond
      (anomaly/any-anomaly? retained) retained
      (anomaly/any-anomaly? recorded) recorded
      :else retained)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish-terminal! [ctx output]
  (flow/continue (artifacts/publish-with-exception-handling ctx :opsv/actuate output)
                 (partial finalize-after-events! ctx)))

(defn- ^{:stratum 1} confirm-dispositions! [ctx output]
  (let [retained (assoc ctx :opsv/disposition-events (:opsv/disposition-events output))]
    (reduce (partial confirm-disposition! retained) output (:opsv/effect-transactions output))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish! [ctx failure]
  (let [retained (model/retained-output failure)]
    (model/attach-evidence failure
      (finalization/call-with-exception-handling retained
        #(flow/continue (confirm-dispositions! ctx retained)
                        (partial publish-terminal! ctx))))))

(comment
  (model/retained-output {}))
