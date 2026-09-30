;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.reliability.dependency-signal
  "Pure dependency-health projection into a degradation transition signal."
  (:require [ai.miniforge.reliability.degradation-config :as config]
            [ai.miniforge.reliability.messages :as messages]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} labels [dependencies]
  (->> dependencies (keep #(some-> % :dependency/id name)) sort vec))

(defn- ^{:stratum 0} prioritized-status [dependencies config]
  (some (fn [status]
          (when (some #(= status (:dependency/status %)) dependencies) status))
        (:dependency-status-precedence config)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} message [status dependencies]
  (let [listing (str/join ", " (labels dependencies))
        params {:dependencies listing}]
    (messages/t (case status
                  (:operator-action-required :misconfigured) :degradation/dependency-operator-action
                  :unavailable :degradation/dependency-unavailable
                  :degradation/dependency-degraded)
                params)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} signal [dependency-health policy]
  (let [dependencies (filterv #(not= :healthy (:dependency/status %)) (vals dependency-health))
        status (prioritized-status dependencies policy)
        mode (get-in policy [:dependency-status->mode status])
        event (get-in policy [:dependency-status->event status])
        description (when status (message status dependencies))
        ids (labels dependencies)
        trigger (when (= mode :safe-mode) event)
        details (when (= mode :safe-mode) description)]
    (when (and status mode event)
      (config/signal mode event description
                     {:dependency/status status
                      :dependency/ids ids
                      :safe-mode-trigger trigger
                      :safe-mode-details details}))))
