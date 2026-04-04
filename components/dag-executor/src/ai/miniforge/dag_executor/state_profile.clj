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

(ns ai.miniforge.dag-executor.state-profile
  "State profiles for DAG task workflows.

   The kernel owns only the profile schema, normalization, and profile
   resolution. Application layers own profile-provider configuration and pass it
   into DAG construction explicitly."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]))

(defn build-profile
  [{profile-id :profile/id
    :keys [task-statuses terminal-statuses success-terminal-statuses
           valid-transitions event-mappings]
    :as profile}]
  (let [terminal-statuses (set terminal-statuses)
        success-terminal-statuses (set success-terminal-statuses)]
    (assoc profile
           :profile/id profile-id
           :task-statuses (set task-statuses)
           :terminal-statuses terminal-statuses
           :success-terminal-statuses success-terminal-statuses
           :valid-transitions (into {}
                                    (map (fn [[from-status targets]]
                                           [from-status (set targets)]))
                                    valid-transitions)
           :event-mappings event-mappings
           :failure-terminal-statuses (set/difference terminal-statuses
                                                     success-terminal-statuses))))

(def kernel-registry-resource
  "Classpath resource for the kernel-owned default provider."
  "config/dag-executor/state-profiles/registry.edn")

(defn read-edn-resource
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (-> resource slurp edn/read-string)))

(defn resolve-provider-profile
  [profile-id profile-definition]
  (let [normalized-profile
        (cond
          (string? profile-definition)
          (some-> profile-definition read-edn-resource build-profile)

          (map? profile-definition)
          (build-profile (assoc profile-definition
                                :profile/id (or (:profile/id profile-definition)
                                                profile-id)))

          :else nil)]
    (when normalized-profile
      [profile-id normalized-profile])))

(defn build-provider
  [{:keys [default-profile profiles] :as provider}]
  (let [normalized-profiles
        (into {}
              (keep (fn [[profile-id profile-definition]]
                      (resolve-provider-profile profile-id profile-definition)))
              profiles)
        fallback-profile-id (or default-profile
                                (first (keys normalized-profiles))
                                :kernel)]
    (assoc provider
           :default-profile fallback-profile-id
           :profiles normalized-profiles)))

(defn load-provider
  []
  (build-provider
   (or (read-edn-resource kernel-registry-resource)
       {:default-profile :kernel
        :profiles {:kernel "config/dag-executor/state-profiles/kernel.edn"}})))

(def ^:private kernel-provider
  (delay (load-provider)))

(defn default-provider
  []
  @kernel-provider)

(defn available-profile-ids
  ([] (available-profile-ids (default-provider)))
  ([provider]
   (keys (:profiles (if provider
                      (build-provider provider)
                      (default-provider))))))

(defn default-profile-id
  ([] (default-profile-id (default-provider)))
  ([provider]
   (or (:default-profile (if provider
                          (build-provider provider)
                          (default-provider)))
       :kernel)))

(defn resolve-profile
  ([profile]
   (resolve-profile (default-provider) profile))
  ([provider profile]
   (let [resolved-provider (if provider
                             (build-provider provider)
                             (default-provider))
         fallback-id (default-profile-id resolved-provider)
         fallback-profile (get-in resolved-provider [:profiles fallback-id])]
     (cond
       (nil? profile) fallback-profile
       (keyword? profile) (or (get-in resolved-provider [:profiles profile])
                              fallback-profile)
       (map? profile) (build-profile profile)
       :else fallback-profile))))

(defn default-profile
  []
  (resolve-profile nil))
