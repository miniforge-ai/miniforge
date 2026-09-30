;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.evidence-checkpoint
  "Preserve domain values across the shared workflow's text-oriented checkpoints."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.evidence-snapshot :as snapshot]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} persistence-failure [ctx]
  (some #(when (anomaly/anomaly? %) %)
        (map (get ctx :execution/input {}) [:opsv/evidence-snapshot :opsv/terminal-snapshot])))

(defn- ^{:stratum 0} persist-terminal [ctx]
  (if (and (= :opsv/actuate (get-in ctx [:phase :name]))
           (= :error (get-in ctx [:phase :result :status])))
    (assoc-in ctx [:execution/input :opsv/terminal-snapshot]
              (snapshot/encode ctx :terminal-checkpoint (select-keys (:phase ctx) [:name :result])))
    ctx))

(defn ^{:stratum 0} restore-terminal [ctx]
  (if-not (and (map? (:execution/input ctx))
               (contains? (:execution/input ctx) :opsv/terminal-snapshot))
    ctx
    (let [phase (snapshot/decode ctx :terminal-checkpoint
                                 (get-in ctx [:execution/input :opsv/terminal-snapshot]))]
      (if (anomaly/anomaly? phase) phase (assoc ctx :phase phase)))))

(defn ^{:stratum 0} restore [ctx]
  (let [input (:execution/input ctx)]
    (if-not (contains? input :opsv/evidence-snapshot)
      (:opsv/evidence-assembly input)
      (snapshot/decode ctx :checkpoint (:opsv/evidence-snapshot input)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} persist [ctx assembly]
  (let [encoded (snapshot/encode ctx :checkpoint assembly)]
    (-> ctx
        (assoc-in [:execution/input :opsv/evidence-assembly] assembly)
        (assoc-in [:execution/input :opsv/evidence-snapshot] encoded)
        persist-terminal)))
