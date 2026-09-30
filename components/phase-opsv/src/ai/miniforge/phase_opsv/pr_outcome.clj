;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-outcome
  "Retain completed phase material and actual transaction state on unsuccessful PR paths."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.pr-model :as model]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} retained-output [output transaction]
  (let [record (:opsv/actuation-record output)]
    (if (effect/valid? transaction)
      (if (= :succeeded (:effect/state transaction))
        (merge output (model/outcome record transaction))
        (assoc output :opsv/effect-transactions [transaction]
                      :opsv/actuation-record
                      (assoc record :effective-actuation-mode :none
                                    :governed-effects [(model/governed-effect transaction)])))
      (assoc-in output [:opsv/actuation-record :effective-actuation-mode] :none))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} retain-failure [output failure]
  (let [retained (-> (retained-output output (get-in failure [:anomaly/data :effect/transaction]))
                     (assoc :opsv/phase-failure (select-keys failure [:anomaly/type :anomaly/message])))]
    (assoc-in failure [:anomaly/data :opsv/phase-output] retained)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} attach [output transaction]
  (cond
    (true? (get-in transaction [:anomaly/data :opsv/stopped?]))
    (assoc (retained-output output (get-in transaction [:anomaly/data :effect/transaction])) :opsv/stopped? true)
    (anomaly/anomaly? transaction) (retain-failure output transaction)
    :else (let [projected (model/outcome (:opsv/actuation-record output) transaction)]
            (if (anomaly/anomaly? projected)
              (retain-failure output projected)
              (merge output projected)))))
