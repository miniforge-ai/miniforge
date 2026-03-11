(ns ai.miniforge.dag-executor.state-profile
  "State profiles for DAG task workflows.

   The kernel owns only the profile schema, normalization, and resource-backed
   resolution. Concrete profile data lives in classpath resources so project
   layers can override the loaded registry without embedding configuration in
   kernel code."
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

(def registry-resource
  "Classpath resource that identifies which profiles are available."
  "config/dag-executor/state-profiles/registry.edn")

(defn read-edn-resource
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (-> resource slurp edn/read-string)))

(defn load-profile-registry
  []
  (or (read-edn-resource registry-resource)
      {:default-profile :kernel
       :profiles {:kernel "config/dag-executor/state-profiles/kernel.edn"}}))

(defn available-profile-ids
  []
  (keys (:profiles (load-profile-registry))))

(defn load-profile-definition
  [profile-id]
  (let [registry (load-profile-registry)
        resource-path (get-in registry [:profiles profile-id])]
    (when resource-path
      (read-edn-resource resource-path))))

(defn default-profile-id
  []
  (or (:default-profile (load-profile-registry)) :kernel))

(defn resolve-profile
  [profile]
  (let [fallback-id (default-profile-id)
        fallback-profile (some-> (load-profile-definition fallback-id) build-profile)]
    (cond
      (nil? profile) fallback-profile
      (keyword? profile) (or (some-> (load-profile-definition profile) build-profile)
                             fallback-profile)
      (map? profile) (build-profile profile)
      :else fallback-profile)))

(defn default-profile
  []
  (resolve-profile nil))
