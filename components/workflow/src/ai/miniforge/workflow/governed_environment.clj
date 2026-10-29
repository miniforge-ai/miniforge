;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.governed-environment
  "Acquire a capsule only after securing its host worktree; transfer both or neither."
  (:require [ai.miniforge.workflow.boundary.environment-ownership :as ownership]
            [ai.miniforge.workflow.environment-records :as records]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} attach-capsule [worktree executor workflow-id mode config ports]
  (when-let [capsule (records/build executor workflow-id mode config ports)]
    (assoc capsule
           :worktree-path (:worktree-path worktree)
           :worktree-executor (:executor worktree)
           :worktree-environment-id (:environment-id worktree))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} acquire! [executor workflow-id mode config ports]
  (let [registry ((:create-registry ports) {:worktree {}})
        worktree-executor ((:select-exec ports) registry :preferred :worktree)]
    (when-let [worktree (when worktree-executor
                         (records/build worktree-executor workflow-id mode config ports))]
      (ownership/transfer! worktree-executor (:environment-id worktree)
                           (partial attach-capsule worktree executor workflow-id mode config ports)))))

(comment ::governed-acquisition-lifetime)
