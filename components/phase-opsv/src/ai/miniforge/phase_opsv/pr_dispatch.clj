;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-dispatch
  "Recheck live authority and stop admission immediately before the provider POST."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.opsv-provider-github.interface :as provider]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.pr-stop :as stop]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} authorized-with-exception-handling [runtime issued record]
  (try
    (let [current (grant/current (:authority-directory runtime) (:grant/id issued))]
      (if (anomaly/anomaly? current)
        current
        (and current
             (grant/authorized?
              (grant/authorize current {:effect/scope (:effect/proposal record) :usage/count 1}
                               ((:clock runtime)))))))
    (catch InterruptedException _ (.interrupt (Thread/currentThread)) false)
    (catch Exception _ false)
    (catch Error _ (anomaly/anomaly :fatal (msg/ts :pr/authority-refused) {}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} dispatch! [runtime issued record operation]
  (let [result (actuation/at-mutation-boundary!
                (:fence runtime)
                (fn []
                  (let [allowed (authorized-with-exception-handling runtime issued record)]
                    (if (and (true? allowed) (not (stop/stopped? runtime)))
                      (operation)
                      (cond-> (stop/refusal :pr/authority-refused)
                        (anomaly/anomaly? allowed) (assoc :effect/observed {:pr/authority-failure allowed}))))))]
    (if (true? (get-in result [:anomaly/data :opsv/stopped?]))
      (stop/refusal :pr/stopped)
      result)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create! [runtime issued record payload]
  (stop/settle! runtime issued (:effect/updated-at record)
    (if (stop/stopped? runtime)
      (stop/refusal :pr/stopped)
      (provider/create-pr! (:provider runtime) record payload
                          (partial dispatch! runtime issued record)))))

(comment
  (stop/refusal :pr/stopped))
