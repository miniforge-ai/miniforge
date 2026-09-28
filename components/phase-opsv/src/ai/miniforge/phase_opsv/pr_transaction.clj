;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-transaction
  "Audit a durable proposal before committing; audit the returned disposition."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.pr-audit :as audit]
            [ai.miniforge.phase-opsv.pr-dispatch :as dispatch]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record-failure! [runtime ctx id failure]
  (let [current (effect/read-record (:effects-directory runtime) id)]
    (if (and current (not (anomaly/anomaly? current)))
      (let [recorded (audit/record! ctx current)]
        (cond-> (assoc-in failure [:anomaly/data :effect/transaction] current)
          (anomaly/anomaly? recorded) (assoc-in [:anomaly/data :opsv/audit-failure] recorded)))
      (assoc-in failure [:anomaly/data :effect/id] id))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} commit! [runtime ctx issued proposed]
  (let [id (:effect/id proposed)
        result (actuation/commit-pr! (:effects-directory runtime) (:authority-directory runtime)
                                    id (:grant/id issued) (:clock runtime)
                                    (partial dispatch/create! runtime issued))]
    (if (anomaly/anomaly? result)
      (record-failure! runtime ctx id result)
      (audit/record! ctx result))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute! [runtime ctx candidate decision issued now]
  (flow/continue
   (actuation/propose-pr! (:effects-directory runtime) candidate (:grant/id issued) decision now)
   #(flow/continue (audit/record! ctx %) (partial commit! runtime ctx issued))))

(comment
  (execute! {} {} {} {} {} nil))
