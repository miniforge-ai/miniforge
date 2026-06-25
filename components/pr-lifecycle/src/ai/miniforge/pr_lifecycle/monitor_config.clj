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

(ns ai.miniforge.pr-lifecycle.monitor-config
  "Load shared PR monitor defaults from EDN configuration."
  (:require
   [ai.miniforge.schema.interface :as schema]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas + config loading

(def MonitorDefaults
  [:map
   [:poll-interval-ms nat-int?]
   [:self-author {:optional true} [:maybe :string]]
   [:max-fix-attempts-per-comment pos-int?]
   [:max-total-fix-attempts-per-pr pos-int?]
   [:abandon-after-hours pos-int?]])

(def MonitorConfig
  [:map
   [:pr-monitor/defaults MonitorDefaults]])

(defn- validate!
  [result-schema value]
  (schema/validate result-schema value))

(defn- load-monitor-config
  []
  (if-let [res (io/resource "config/pr-monitor/defaults.edn")]
    (->> res slurp edn/read-string (validate! MonitorConfig))
    (throw (ex-info "Missing classpath resource: config/pr-monitor/defaults.edn"
                    {:hint "Add components/pr-lifecycle/resources to your classpath"}))))

(def ^:private monitor-config
  (delay (load-monitor-config)))

;------------------------------------------------------------------------------ Layer 1
;; Public helpers

(defn monitor-defaults
  "Return the shared PR monitor defaults map."
  []
  (:pr-monitor/defaults @monitor-config))

(defn budget-defaults
  "Return just the budget-related defaults used by the monitor loop."
  []
  (select-keys (monitor-defaults)
               [:max-fix-attempts-per-comment
                :max-total-fix-attempts-per-pr
                :abandon-after-hours]))
