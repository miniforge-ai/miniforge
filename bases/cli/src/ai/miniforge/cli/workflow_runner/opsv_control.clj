;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.workflow-runner.opsv-control
  "Host-owned OPSV stop domain, independent of event-stream delivery."
  (:require [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.cli.main.util :as composition]
            [ai.miniforge.cli.messages :as messages])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} providers []
  (into {} (map (fn [[key var-name]]
                  [key (composition/optional-composition-var 'ai.miniforge.phase-opsv.interface var-name)]))
        {:create 'create-run-supervisor :stop! 'stop-supervised-runs! :register! 'register-run-control!}))

(defn ^{:stratum 0} stop! [supervisor _signal]
  ((:stop! supervisor) (:handle supervisor) (Instant/now)))

(defn ^{:stratum 0} register! [supervisor workflow-id directory request-abort!]
  (if (anomaly/anomaly? supervisor) supervisor
      ((:register! supervisor) (:handle supervisor) workflow-id directory request-abort!)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create []
  (let [ports (providers)]
    (if (every? ifn? (vals ports))
      (assoc ports :handle ((:create ports)))
      (anomaly/anomaly :unavailable (messages/t :runtime/opsv-unavailable) {}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} context [event-stream]
  (let [supervisor (create)
        config (when-not (anomaly/anomaly? supervisor)
                 {:degradation {:before-safe-mode! (partial stop! supervisor)}})]
    (assoc (agent/create-meta-loop-context event-stream config)
           :opsv/supervisor supervisor)))
