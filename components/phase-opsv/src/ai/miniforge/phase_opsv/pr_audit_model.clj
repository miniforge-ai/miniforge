;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-audit-model
  "Disposition payloads and lossless pending-audit retention."
  (:require [ai.miniforge.phase-opsv.pr-model :as model]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} payload [transaction]
  (let [envelope (get-in transaction [:effect/proposal :opsv/envelope])
        observed (select-keys (:effect/observed transaction) [:pr/url :pr/number :pr/head-sha])]
    {:opsv/governed-effect (model/governed-effect transaction)
     :opsv/effect-state (:effect/state transaction)
     :opsv/decision-envelope envelope
     :opsv/effect-observed observed
     :opsv/effect-failure (:effect/failure transaction)}))

(defn ^{:stratum 0} matching-event [expected records]
  (first (filter #(= expected (select-keys % (keys expected))) records)))

(defn- ^{:stratum 0} append-distinct [records event]
  (into [] (distinct) (conj (vec records) event)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} retain [output failure]
  (let [event (or (get-in failure [:anomaly/data :opsv/disposition-event])
                  (get-in failure [:anomaly/data :opsv/audit-failure :anomaly/data :opsv/disposition-event]))]
    (if (= :opsv.actuation/disposition (:event/type event))
      (update output :opsv/disposition-events append-distinct event)
      output)))

(comment
  (retain {} {}))
