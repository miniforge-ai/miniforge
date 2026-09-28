;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-dispatch
  "Recheck live authority and stop admission immediately before the provider POST."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.opsv-provider-github.interface :as provider]
            [ai.miniforge.phase-opsv.pr-stop :as stop]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} authorized? [runtime issued record]
  (let [current (grant/current (:authority-directory runtime) (:grant/id issued))]
    (and (not (anomaly/anomaly? current)) current
         (grant/authorized?
          (grant/authorize current {:effect/scope (:effect/proposal record) :usage/count 1}
                           ((:clock runtime)))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} dispatch! [runtime issued record operation]
  (let [result (actuation/at-mutation-boundary!
                (:fence runtime)
                #(if (authorized? runtime issued record)
                   (operation)
                   (stop/refusal :pr/authority-refused)))]
    (if (true? (get-in result [:anomaly/data :opsv/stopped?]))
      (stop/refuse! runtime issued)
      (stop/settle! runtime issued result))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create! [runtime issued record payload]
  (if (stop/stopped? runtime)
    (stop/refuse! runtime issued)
    (provider/create-pr! (:provider runtime) record payload
                        (partial dispatch! runtime issued record))))

(comment
  (stop/refusal :pr/stopped))
