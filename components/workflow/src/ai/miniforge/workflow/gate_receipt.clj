;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.gate-receipt
  "Validate the identity and position of an acknowledged phase decision."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} failure [reason]
  (anomaly/anomaly :unavailable (messages/t :gate/publication-unconfirmed)
                   {:gate/publication-reason reason}))

(defn- ^{:stratum 0} matching? [event receipt]
  (let [fields [:event/id :event/type :event/version :workflow/id :scope/type
                :gate/phase :envelope/id :gate/decision-envelope]]
    (and (map? receipt) (not (:rejected? receipt))
         (= (select-keys event fields) (select-keys receipt fields))
         (nat-int? (:event/sequence-number receipt))
         (or (not (contains? event :event/sequence-number))
             (= (:event/sequence-number event) (:event/sequence-number receipt))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} acknowledged [event receipt]
  (cond
    (anomaly/any-anomaly? receipt) receipt
    (matching? event receipt) receipt
    :else (failure :unconfirmed)))

(comment
  (acknowledged {} nil))
