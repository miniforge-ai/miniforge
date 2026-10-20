;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.evidence-checkpoint
  "Preserve domain values across the shared workflow's text-oriented checkpoints."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.evidence-base :as base]
            [ai.miniforge.phase-opsv.evidence-snapshot :as snapshot]
            [ai.miniforge.phase-opsv.terminal-model :as terminal]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} persistence-failure [ctx]
  (some #(when (anomaly/any-anomaly? %) %)
        (map (get ctx :execution/input {}) [:opsv/evidence-snapshot :opsv/terminal-snapshot])))

(defn- ^{:stratum 0} terminal-record [ctx]
  (assoc (select-keys (:phase ctx) [:name :result :status :ended-at :duration-ms :metrics])
         :opsv/evidence-base (base/bundle ctx)))

(defn ^{:stratum 0} terminal-pending? [ctx]
  (and (map? (:execution/input ctx))
       (contains? (:execution/input ctx) :opsv/terminal-snapshot)))

(defn ^{:stratum 0} restore [ctx]
  (let [input (:execution/input ctx)]
    (if-not (contains? input :opsv/evidence-snapshot)
      (:opsv/evidence-assembly input)
      (snapshot/decode ctx :checkpoint (:opsv/evidence-snapshot input)))))

(defn- ^{:stratum 0} restore-phase [ctx phase]
  (if (anomaly/any-anomaly? phase) phase
    (-> ctx
        (assoc :phase (dissoc phase :opsv/evidence-base))
        (assoc :opsv/recovered-evidence-base (:opsv/evidence-base phase))
        (assoc-in [:execution/opts :opsv/evidence-base] (:opsv/evidence-base phase)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} restore-terminal [ctx]
  (if-not (terminal-pending? ctx)
    ctx
    (restore-phase ctx (snapshot/decode ctx :terminal-checkpoint
                                        (get-in ctx [:execution/input :opsv/terminal-snapshot])))))

(defn- ^{:stratum 1} persist-terminal [ctx]
  (if (and (contains? (:execution/opts ctx) :opsv/evidence-base)
           (= :opsv/actuate (get-in ctx [:phase :name]))
           (= :error (get-in ctx [:phase :result :status]))
           (terminal/recoverable? (get-in ctx [:phase :result :output])))
    (assoc-in ctx [:execution/input :opsv/terminal-snapshot]
              (snapshot/encode ctx :terminal-checkpoint
                               (terminal-record ctx)))
    ctx))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} persist [ctx assembly]
  (let [failed? (anomaly/any-anomaly? assembly)
        encoded (if failed? assembly (snapshot/encode ctx :checkpoint assembly))]
    (persist-terminal
     (cond-> (assoc-in ctx [:execution/input :opsv/evidence-snapshot] encoded)
       (not failed?) (assoc-in [:execution/input :opsv/evidence-assembly] assembly)))))
