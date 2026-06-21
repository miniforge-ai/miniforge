;; Copyright 2025 miniforge.ai
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.web-dashboard.config
  "Validated load of the web-dashboard deployment/session defaults."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Fail-fast resource loading

(def ^:private defaults-resource "config/web-dashboard/defaults.edn")

(def ^:private required-keys
  [:port :session-cookie-name :session-ttl-ms :stale-threshold-ms :max-recent-workflows])

(defn- load-config
  "Read an EDN config resource, failing fast with a clear ex-info when the
   resource is absent from the classpath, malformed, not a map, or missing
   a required key — rather than a low-signal NPE at load."
  [path required-keys]
  (let [url (io/resource path)]
    (when (nil? url)
      (throw (ex-info (str "Missing config resource on classpath: " path)
                      {:config/resource path})))
    (let [parsed (try
                   (edn/read-string (slurp url))
                   (catch Exception e
                     (throw (ex-info (str "Malformed EDN config resource: " path)
                                     {:config/resource path} e))))]
      (when-not (map? parsed)
        (throw (ex-info (str "Config resource is not a map: " path) {:config/resource path})))
      (let [missing (remove #(contains? parsed %) required-keys)]
        (when (seq missing)
          (throw (ex-info (str "Config resource " path " missing keys: " (vec missing))
                          {:config/resource path :config/missing-keys (vec missing)})))
        parsed))))

(def defaults
  "Web dashboard deployment and session defaults, validated at load."
  (load-config defaults-resource required-keys))
