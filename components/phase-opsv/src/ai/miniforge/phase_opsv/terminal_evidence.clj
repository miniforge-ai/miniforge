;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.terminal-evidence
  "Finalize unsuccessful actuation without repeating it or reporting phase success."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.artifact-boundary :as artifacts]
            [ai.miniforge.phase-opsv.events :as events]
            [ai.miniforge.phase-opsv.finalization-boundary :as finalization]
            [ai.miniforge.phase-opsv.finalization-config :as config]
            [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.pr-audit :as audit]
            [ai.miniforge.phase-opsv.lifecycle-result :as result]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} finalize-after-events! [ctx output]
  (let [publication (events/emit-phase-events! ctx :opsv/actuate output)]
    (if (anomaly/anomaly? publication) publication (finalization/finalize! ctx output))))

(defn- ^{:stratum 0} confirm-disposition! [ctx retained transaction]
  (if (anomaly/anomaly? retained)
    retained
    (let [recorded (audit/record! ctx transaction)]
      (if (anomaly/anomaly? recorded) recorded retained))))

(defn- ^{:stratum 0} attach-evidence [failure published]
  (if (anomaly/anomaly? published)
    (assoc-in failure [:anomaly/data :opsv/evidence-failure] published)
    (assoc-in failure [:anomaly/data :opsv/phase-output] published)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish-terminal! [ctx output]
  (flow/continue (artifacts/publish-with-exception-handling ctx :opsv/actuate output)
                 (partial finalize-after-events! ctx)))

(defn- ^{:stratum 1} confirm-dispositions! [ctx output]
  (reduce (partial confirm-disposition! ctx) output (:opsv/effect-transactions output)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} complete-failure! [ctx phase-key phase-result]
  (let [failure (:output phase-result)
        retained (get-in failure [:anomaly/data :opsv/phase-output])]
    (if (and (= :opsv/actuate phase-key) (config/enabled? ctx)
             (anomaly/anomaly? failure) (:opsv/phase-failure retained))
      (result/phase-result
       (attach-evidence failure
         (finalization/call-with-exception-handling retained
           #(flow/continue (confirm-dispositions! ctx retained) (partial publish-terminal! ctx)))))
      phase-result)))
