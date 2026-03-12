(ns ai.miniforge.workflow.state-profile
  "Classpath-loaded workflow state-profile provider helpers.

   Concrete profile resources are app-owned and composed onto the classpath by
   the active project."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

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
