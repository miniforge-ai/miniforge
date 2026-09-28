;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-model
  "Correlate one prepared target per run and project confirmed PR outcomes."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.messages :as msg]
            [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} effect-id [workflow-id repository]
  (UUID/nameUUIDFromBytes
   (.getBytes (pr-str [:opsv/pr-create workflow-id (str/lower-case (str repository))])
              StandardCharsets/UTF_8)))

(defn ^{:stratum 0} outcome [record transaction]
  (if (= :succeeded (:effect/state transaction))
    {:opsv/actuation-record
     (assoc record :effective-actuation-mode :pr-only
            :governed-effects [{:evidence/effect-id (:effect/id transaction)
                                :evidence/grant-id (:effect/grant-id transaction)
                                :evidence/envelope-id (:effect/envelope-id transaction)}]
            :pr-refs [(get-in transaction [:effect/observed :pr/url])])
     :opsv/effect-transactions [transaction]}
    (anomaly/anomaly :unavailable (msg/ts :pr/unconfirmed)
                     {:effect/transaction transaction})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} candidate [ctx verified target]
  (if (not= (:opsv/policy-hash verified) (:opsv/policy-hash target))
    (anomaly/anomaly :conflict (msg/ts :pr/policy-mismatch) {})
    (assoc (dissoc target :opsv/policy-hash)
           :workflow-run/id (:execution/id ctx)
           :effect/id (effect-id (:execution/id ctx) (:pr/repo target))
           :opsv/evidence-bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])
           :opsv/verification-result (:opsv/verification-result verified))))

(comment
  (effect-id (random-uuid) "example/opsv"))
