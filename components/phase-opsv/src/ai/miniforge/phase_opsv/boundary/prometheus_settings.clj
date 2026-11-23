;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.prometheus-settings
  "Admit explicit operator settings without discovering or echoing credentials."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.config.interface :as config]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.prometheus-schema :as schema]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [clojure.string :as str]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]])
  (:import [java.net URI]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private maximum-port 65535)

(def ^{:stratum 0} ^:private minimum-port 1)

(defn- ^{:stratum 0} canonical-endpoint [url]
  (let [scheme (.getScheme (URI. url))]
    (str (str/lower-case scheme) (subs url (count scheme)))))

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :prometheus/invalid-settings) {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} failed-settings! [failure]
  (if (cleanup/fatal-cause failure)
    (cleanup/after-failure! failure (constantly nil))
    (invalid)))

(defn- ^{:stratum 1} endpoint? [settings]
  (let [uri (URI. (:prometheus/base-url settings))
        scheme (some-> (.getScheme uri) str/lower-case)
        port (.getPort uri)
        secure? (= "https" scheme)]
    (and (#{"http" "https"} scheme) (.getHost uri)
         (or (= -1 port) (<= minimum-port port maximum-port))
         (nil? (.getRawUserInfo uri)) (nil? (.getRawQuery uri)) (nil? (.getRawFragment uri))
         (or secure? (not (contains? settings :prometheus/bearer-token))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} load-settings
  "Load schema-checked defaults and explicit overrides at the configuration boundary."
  [overrides]
  (try+
    (let [defaults (config/load-config-resource "config/phase-opsv/prometheus.edn")
          settings (merge defaults overrides)]
      (if (and (map? overrides) (m/validate schema/Settings settings) (endpoint? settings))
        (update settings :prometheus/base-url canonical-endpoint)
        (invalid)))
    (catch Object _ (failed-settings! (:throwable &throw-context)))))

(comment ::explicit-prometheus-endpoint)
