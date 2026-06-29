;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
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

(ns ai.miniforge.policy-calibration.config
  "Calibration configuration as data: read + Malli-validate config.edn."
  (:require [malli.core :as m]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def Config
  [:map
   [:model        [:map [:label string?] [:backend keyword?] [:model string?]]]
   [:trials       pos-int?]
   [:runs         pos-int?]
   [:max-parallel pos-int?]
   [:gate-bar     [:map
                   [:max-clean-fp nat-int?]
                   [:min-recall   [:and number? [:>= 0] [:<= 1]]]]]
   [:pack-paths   [:vector string?]]
   [:corpus-root  string?]
   [:record-out   string?]])

(def ^:private config-resource "policy-calibration/config.edn")

(defn- invalid-config-ex
  [message reason data cause]
  (ex-info message
           (merge {:classpath/resource config-resource
                   :config/error :invalid-config
                   :config/invalid-config-reason reason}
                  data)
           cause))

(defn load-config
  "Read and validate the calibration config from the classpath. Throws
   ex-info on malformed config (a setup/programmer error)."
  []
  (let [resource (io/resource config-resource)]
    (when-not resource
      (throw (invalid-config-ex "Missing policy-calibration config resource"
                                :missing-resource
                                {}
                                nil)))
    (let [cfg (try
                (edn/read-string (slurp resource :encoding "UTF-8"))
                (catch java.io.IOException e
                  (throw (invalid-config-ex "Unreadable policy-calibration config resource"
                                            :unreadable-resource
                                            {}
                                            e)))
                (catch InterruptedException e
                  (.interrupt (Thread/currentThread))
                  (throw e))
                (catch Exception e
                  (throw (invalid-config-ex "Malformed policy-calibration config resource"
                                            :malformed-edn
                                            {}
                                            e))))]
      (when-not (map? cfg)
        (throw (invalid-config-ex "Policy-calibration config must be a map"
                                  :not-a-map
                                  {:value cfg}
                                  nil)))
      (when-not (m/validate Config cfg)
        (throw (invalid-config-ex "Invalid policy-calibration config"
                                  :schema-validation
                                  {:errors (m/explain Config cfg)
                                   :value cfg}
                                  nil)))
      cfg)))

(comment
  (load-config)
  :leave-this-here)
