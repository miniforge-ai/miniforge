(ns ai.miniforge.workflow.state-profile
  "Workflow-owned state-profile provider configuration."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def registry-resource
  "Classpath resource for workflow-owned state-profile configuration."
  "config/workflow/state-profiles/registry.edn")

(defn read-edn-resource
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (-> resource slurp edn/read-string)))

(defn load-state-profile-provider
  []
  (some-> registry-resource
          read-edn-resource
          dag/build-state-profile-provider))

(defn available-state-profile-ids
  []
  (dag/available-state-profile-ids (load-state-profile-provider)))

(defn default-state-profile-id
  []
  (:default-profile (load-state-profile-provider)))

(defn resolve-state-profile
  [profile]
  (dag/resolve-state-profile (load-state-profile-provider) profile))
