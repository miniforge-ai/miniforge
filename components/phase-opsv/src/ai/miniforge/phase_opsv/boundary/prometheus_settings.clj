;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.prometheus-settings
  "Admit explicit operator settings without discovering or echoing credentials."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.config.interface :as config]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.prometheus-schema :as schema]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]])
  (:import [java.net URI]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :prometheus/invalid-settings) {}))

(defn- ^{:stratum 0} endpoint? [settings]
  (let [uri (URI. (:prometheus/base-url settings))
        scheme (.getScheme uri)
        secure? (= "https" scheme)]
    (and (#{"http" "https"} scheme) (.getHost uri)
         (nil? (.getRawUserInfo uri)) (nil? (.getRawQuery uri)) (nil? (.getRawFragment uri))
         (or secure? (not (contains? settings :prometheus/bearer-token))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} load-settings
  "Load schema-checked defaults and explicit overrides at the configuration boundary."
  [overrides]
  (try+
    (let [defaults (config/load-config-resource "config/phase-opsv/prometheus.edn")
          settings (merge defaults overrides)]
      (if (and (map? overrides) (m/validate schema/Settings settings) (endpoint? settings))
        settings
        (invalid)))
    (catch InterruptedException thrown (throw thrown))
    (catch Exception _ (invalid))))

(comment ::explicit-prometheus-endpoint)
