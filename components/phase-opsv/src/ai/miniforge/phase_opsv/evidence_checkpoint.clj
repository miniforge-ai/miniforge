;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.evidence-checkpoint
  "Preserve domain values across the shared workflow's text-oriented checkpoints."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.evidence-base :as base]
            [ai.miniforge.phase-opsv.evidence-snapshot :as snapshot]
            [ai.miniforge.phase-opsv.lifecycle-outcome :as outcome]
            [ai.miniforge.phase-opsv.post-actuation-checkpoint :as post-actuation]
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

(defn- ^{:stratum 0} restore-gate-failed-phase
  "Rebuild the failed :opsv/actuate phase from the post-actuation snapshot and
   the gate-failure record, for a run whose terminal snapshot was not kept."
  [ctx]
  (let [restored (post-actuation/restore ctx)]
    (if (anomaly/any-anomaly? restored)
      restored
      (let [failure (post-actuation/gate-failure ctx)
            output (:opsv/recovered-actuation-output restored)
            phase-failure (anomaly/anomaly (:anomaly/type failure) (:anomaly/message failure)
                                           {:opsv/phase-output output})]
        (-> restored
            (dissoc :opsv/recovered-actuation-output)
            (assoc :phase {:name :opsv/actuate :status :failed
                           :result (outcome/phase-result phase-failure)}))))))

(defn- ^{:stratum 0} restore-phase [ctx phase]
  (if (anomaly/any-anomaly? phase) phase
    (-> ctx
        (assoc :phase (dissoc phase :opsv/evidence-base))
        (assoc :opsv/recovered-evidence-base (:opsv/evidence-base phase))
        (assoc-in [:execution/opts :opsv/evidence-base] (:opsv/evidence-base phase)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} restore-terminal
  "Restore the failed actuation phase from its terminal snapshot or, when a
   gate-failed actuation's terminal snapshot could not be kept, from the
   post-actuation snapshot and the gate-failure record."
  [ctx]
  (cond
    (terminal-pending? ctx)
    (restore-phase ctx (snapshot/decode ctx :terminal-checkpoint
                                        (get-in ctx [:execution/input :opsv/terminal-snapshot])))
    (post-actuation/gate-failure-recoverable? ctx) (restore-gate-failed-phase ctx)
    :else ctx))

(defn- ^{:stratum 1} persist-terminal [ctx]
  (if (and (contains? (:execution/opts ctx) :opsv/evidence-base)
           (= :opsv/actuate (get-in ctx [:phase :name]))
           (= :error (get-in ctx [:phase :result :status]))
           (terminal/recoverable? (get-in ctx [:phase :result :output])))
    (let [encoded (snapshot/encode ctx :terminal-checkpoint (terminal-record ctx))]
      ;; For an actuation its gate failed, an encoding failure (for example past
      ;; the 16 MiB bound) must not shadow the usable post-actuation snapshot:
      ;; recovery restores that snapshot and the recorded gate failure instead.
      ;; Other terminal encoding failures still surface on recovery.
      (if (and (anomaly/any-anomaly? encoded) (post-actuation/gate-failure-recoverable? ctx))
        ctx
        (assoc-in ctx [:execution/input :opsv/terminal-snapshot] encoded)))
    ctx))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} persist [ctx assembly]
  (let [failed? (anomaly/any-anomaly? assembly)
        encoded (if failed? assembly (snapshot/encode ctx :checkpoint assembly))]
    (persist-terminal
     (cond-> (assoc-in ctx [:execution/input :opsv/evidence-snapshot] encoded)
       (not failed?) (assoc-in [:execution/input :opsv/evidence-assembly] assembly)))))
