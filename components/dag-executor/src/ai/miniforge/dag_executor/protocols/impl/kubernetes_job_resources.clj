;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-job-resources
  "Project legacy resource and environment values without dispatching provider requests."
  (:require [ai.miniforge.dag-executor.protocols.impl.kubernetes-defaults :as defaults]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} default-workdir
  "Preserve the legacy working directory from packaged defaults."
  []
  (:workdir (defaults/data)))

(defn ^{:stratum 0} build-env-vars
  "Use Kubernetes name/value entries, stringifying values as the old executor did."
  [env-map]
  (mapv (fn [[key value]] {:name (name key)
                          :value (str value)}) env-map))

(defn- ^{:stratum 0} project-resource [{:keys [memory cpu]}]
  (cond-> {}
    memory (assoc :memory memory)
    cpu (assoc :cpu (str cpu))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build-resource-spec
  "Merge legacy defaults, keep memory/CPU only, and stringify truthy CPU values."
  [resources]
  (let [defaults (:resources (defaults/data))
        merged (merge-with merge defaults resources)]
    (update-vals (select-keys merged [:limits :requests]) project-resource)))

(comment (build-resource-spec nil))
