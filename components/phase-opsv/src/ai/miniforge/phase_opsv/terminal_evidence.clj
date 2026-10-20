;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.terminal-evidence
  "Finalize unsuccessful actuation without repeating it or reporting phase success."
  (:require [ai.miniforge.phase-opsv.events :as events]
            [ai.miniforge.phase-opsv.evidence-checkpoint :as checkpoint]
            [ai.miniforge.phase-opsv.finalization-boundary :as finalization]
            [ai.miniforge.phase-opsv.finalization :as assembly]
            [ai.miniforge.phase-opsv.finalization-config :as config]
            [ai.miniforge.phase-opsv.post-actuation-checkpoint :as post-actuation]
            [ai.miniforge.phase-opsv.terminal-model :as model]
            [ai.miniforge.phase-opsv.terminal-publication :as publication]
            [ai.miniforge.phase-opsv.lifecycle-result :as result]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} retained-events? [ctx output]
  (and (contains? output :opsv/actuation-events)
       (or (post-actuation/pending? ctx) (checkpoint/terminal-pending? ctx))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} retain-failure-events [ctx failure]
  (let [output (model/retained-output failure)
        retained (if (retained-events? ctx output) output
                   (events/retain-actuation-events ctx output))]
    (assoc-in failure [:anomaly/data :opsv/phase-output] retained)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} complete-failure! [ctx phase-key phase-result]
  (let [classified (model/classify-failure (:output phase-result))]
    (cond
      (not (and (= :opsv/actuate phase-key) (config/enabled? ctx)
                (model/recoverable? classified))) phase-result
      (= :finalized (:opsv.assembly/status (assembly/assembly ctx)))
      (result/phase-result classified)
      :else
      (result/phase-result
       (finalization/call-with-exception-handling
        (model/retained-output classified)
        #(publication/publish! ctx (retain-failure-events ctx classified)))))))
