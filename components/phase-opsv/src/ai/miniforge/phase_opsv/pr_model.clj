;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-model
  "Correlate one prepared target per run and project confirmed PR outcomes."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.runtime-context :as context]
            [clojure.string :as str]
            [malli.core :as m])
  (:import [java.nio.charset StandardCharsets]
           [java.util Locale UUID]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} effect-id [workflow-id repository]
  (UUID/nameUUIDFromBytes
   (.getBytes (pr-str [:opsv/pr-create workflow-id (.toLowerCase (str repository) Locale/ROOT)])
              StandardCharsets/UTF_8)))

(defn- ^{:stratum 0} nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(defn- ^{:stratum 0} governed-effect [transaction]
  {:evidence/effect-id (:effect/id transaction)
   :evidence/grant-id (:effect/grant-id transaction)
   :evidence/envelope-id (:effect/envelope-id transaction)})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} prepared-candidate [ctx workflow-id verified target]
  (assoc target
         :workflow-run/id workflow-id
         :effect/id (effect-id workflow-id (:pr/repo target))
         :opsv/evidence-bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])
         :opsv/verification-result (:opsv/verification-result verified)))

(defn- ^{:stratum 1} confirmed? [transaction]
  (and (= :effect/pr-create (:effect/class transaction))
       (= :granted (:effect/authority transaction))
       (or (= :succeeded (:effect/state transaction))
           (and (= :reconciled (:effect/state transaction)) (true? (:effect/matched? transaction))))
       (every? uuid? (vals (governed-effect transaction)))
       (nonblank? (get-in transaction [:effect/observed :pr/url]))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} candidate [ctx verified target]
  (let [workflow-id (context/workflow-id ctx)]
    (cond
      (not (and (nonblank? (:opsv/policy-hash verified))
                (= (:opsv/policy-hash verified) (:opsv/policy-hash target))))
      (anomaly/anomaly :conflict (msg/ts :pr/policy-mismatch) {})

      (not (and (uuid? workflow-id) (m/validate opsv/Repository (:pr/repo target))))
      (anomaly/anomaly :invalid-input (msg/ts :pr/invalid-correlation) {})

      :else (prepared-candidate ctx workflow-id verified target))))

(defn ^{:stratum 2} outcome [record transaction]
  (if (confirmed? transaction)
    {:opsv/actuation-record
     (assoc record :effective-actuation-mode :pr-only
            :governed-effects [(governed-effect transaction)]
            :pr-refs [(get-in transaction [:effect/observed :pr/url])])
     :opsv/effect-transactions [transaction]}
    (anomaly/anomaly :unavailable (msg/ts :pr/unconfirmed)
                     {:effect/transaction transaction})))

(comment
  (effect-id (random-uuid) "example/opsv"))
