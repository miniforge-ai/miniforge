;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.effect-registration
  "Account for grant writes before admission can close or cleanup can complete."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.run-control :as control]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} track! [runtime now issued]
  (if-let [handle (:control runtime)]
    (control/track-grant! handle issued now)
    issued))

(defn- ^{:stratum 0} register-same! [runtime issued]
  (let [registered (grant/register! (:authority-directory runtime) issued)]
    (cond
      (anomaly/anomaly? registered) registered
      (and (m/validate grant/ExecutionGrant registered) (= (:grant/id issued) (:grant/id registered))) issued
      :else (anomaly/anomaly :unavailable (msg/ts :effect/registration-unconfirmed)
                             {:grant/id (:grant/id issued) :grant/registered-id (:grant/id registered)}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} register-tracked! [runtime issued now]
  (-> (track! runtime now issued)
      (flow/continue (partial register-same! runtime))
      (flow/continue (partial track! runtime now))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} register! [runtime issued now]
  (if-let [handle (:control runtime)]
    (control/at-registration-boundary! handle (partial register-tracked! runtime issued now))
    (register-tracked! runtime issued now)))

(comment
  (track! {} nil {}))
