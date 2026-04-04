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

(ns ai.miniforge.workflow.state-profile
  "Classpath-loaded workflow state-profile provider helpers.

   Concrete profile resources are app-owned and composed onto the classpath by
   the active project."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [clojure.edn :as edn]))

(def registry-resource
  "Classpath resource for app-owned workflow state-profile configuration."
  "config/workflow/state-profiles/registry.edn")

(defn read-edn-resource
  [resource]
  (some-> resource
          slurp
          edn/read-string))

(defn registry-resources
  []
  (enumeration-seq (.getResources (clojure.lang.RT/baseLoader) registry-resource)))

(defn merge-provider-configs
  [configs]
  (reduce (fn [merged config]
            (cond-> merged
              (:default-profile config) (assoc :default-profile (:default-profile config))
              (:profiles config) (update :profiles merge (:profiles config))))
          {:profiles {}}
          configs))

(defn load-state-profile-provider
  []
  (let [provider-config (->> (registry-resources)
                             (map read-edn-resource)
                             (filter some?)
                             merge-provider-configs)]
    (when (seq (:profiles provider-config))
      (dag/build-state-profile-provider provider-config))))

(defn available-state-profile-ids
  []
  (if-let [provider (load-state-profile-provider)]
    (dag/available-state-profile-ids provider)
    []))

(defn default-state-profile-id
  []
  (some-> (load-state-profile-provider)
          :default-profile))

(defn resolve-state-profile
  [profile]
  (when-let [provider (load-state-profile-provider)]
    (cond
      (nil? profile)
      (get-in provider [:profiles (:default-profile provider)])

      (keyword? profile)
      (get-in provider [:profiles profile])

      :else
      (dag/resolve-state-profile provider profile))))
