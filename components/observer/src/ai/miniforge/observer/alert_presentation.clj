;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.alert-presentation
  "Construct alert values with localized defaults and caller-supplied templates."
  (:require [ai.miniforge.observer.messages :as msg]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} message-keys
  {:phase-gap :alert/phase-gap
   :tool-call-timeout :alert/tool-call-timeout
   :tool-error-rate :alert/tool-error-rate})

(defn- ^{:stratum 0} template [s data]
  (str/replace s #"\{\{([\w][\w/-]*)\}\}" #(str (get data (keyword (second %)) ""))))

(defn- ^{:stratum 0} message-data [data]
  (cond-> data
    (:error-rate data) (assoc :error-percent (format "%.1f" (* 100.0 (double (:error-rate data)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} message [rule data]
  (if-let [s (:alert/message-template rule)]
    (template s data)
    (msg/t (get message-keys (:alert/type rule)) (message-data data))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} alert [rule data]
  {:alert/rule-id (:alert/id rule)
   :alert/type (:alert/type rule)
   :alert/severity (get rule :alert/severity :warning)
   :alert/message (message rule data)
   :alert/data data
   :alert/timestamp (java.util.Date.)})

(comment ::localized-alert-values)
