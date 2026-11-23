;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.kubernetes-target.fixtures)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} target []
  {:cluster "staging"
   :namespace "catalog"
   :namespace-uid "namespace-uid"
   :workload-uid "deployment-uid"})

(defn ^{:stratum 0} job []
  {"apiVersion" "batch/v1"
   "kind" "Job"
   "metadata" {"namespace" "catalog" "name" "run-capsule" "uid" "original-job-uid"}})

(defn ^{:stratum 0} pod [references]
  {"metadata" {"namespace" "catalog" "ownerReferences" references}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} job-reference []
  (let [parent (job)]
    (merge (select-keys parent ["apiVersion" "kind"])
           (select-keys (get parent "metadata") ["name" "uid"])
           {"controller" true})))

(comment ::mirrors-phase-opsv-kubernetes-membership-fixtures-target)
