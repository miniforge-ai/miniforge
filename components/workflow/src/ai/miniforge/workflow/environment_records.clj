;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.environment-records
  "Describe an acquired environment, including its original worktree revision."
  (:require [ai.miniforge.workflow.boundary.environment :as boundary]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} normalized-metadata [environment]
  (let [metadata (:metadata environment)]
    (if (map? metadata) metadata {})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build
  [executor workflow-id mode env-config {:keys [acquire-env! result-ok? result-unwrap]}]
  (let [task-id (if (uuid? workflow-id) workflow-id (random-uuid))
        result (acquire-env! executor task-id env-config)]
    (when (result-ok? result)
      (let [environment (result-unwrap result)
            workdir (:workdir environment)
            base-sha (boundary/head-sha workdir)
            metadata (cond-> (normalized-metadata environment) base-sha (assoc :base-sha base-sha))]
        {:executor executor
         :environment-id (:environment-id environment)
         :worktree-path workdir
         :execution-mode mode
         :environment-metadata metadata}))))

(comment ::acquired-environment-records)
