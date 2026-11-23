;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-settings
  "Explicit HTTPS API target; never discover kubeconfig, credentials or a cluster."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.config.interface :as config]
            [ai.miniforge.phase-opsv.kubernetes-settings-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]])
  (:import [java.net URI]
           [java.util Locale]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private maximum-port 65535)

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :kubernetes/invalid-settings) {}))

(defn- ^{:stratum 0} canonical-endpoint [url]
  (let [scheme (.getScheme (URI. url))]
    (str "https" (subs url (count scheme)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} endpoint? [url]
  (let [uri (URI. url)
        scheme (some-> (.getScheme uri) (.toLowerCase Locale/ROOT))
        port (.getPort uri)]
    (and (= "https" scheme) (.getHost uri)
         (or (= -1 port) (<= 1 port maximum-port))
         (#{"" "/"} (.getRawPath uri))
         (nil? (.getRawUserInfo uri)) (nil? (.getRawQuery uri)) (nil? (.getRawFragment uri)))))

(defn- ^{:stratum 1} failed! [failure]
  (if (cleanup/fatal-cause failure)
    (cleanup/after-failure! failure (constantly nil))
    (invalid)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} load-settings
  ([overrides] (load-settings overrides schema/Settings []))
  ([overrides settings-schema excluded-defaults]
   (try+
     (let [defaults (config/load-config-resource "config/phase-opsv/kubernetes.edn")
           settings (merge (apply dissoc defaults excluded-defaults) overrides)]
       (if (and (map? overrides) (m/validate settings-schema settings)
                (endpoint? (:kubernetes/base-url settings)))
         (update settings :kubernetes/base-url canonical-endpoint)
         (invalid)))
     (catch Object _ (failed! (:throwable &throw-context))))))

(comment ::credential-safe-kubernetes-target)
