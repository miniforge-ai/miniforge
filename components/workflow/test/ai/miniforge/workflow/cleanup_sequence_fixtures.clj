;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.cleanup-sequence-fixtures
  (:require [ai.miniforge.workflow.runner-cleanup :as cleanup]
            [ai.miniforge.workflow.runner-environment :as environment]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} acquired []
  {:executor :capsule :environment-id :capsule-id
   :worktree-executor :worktree :worktree-environment-id :worktree-id})

(defn- ^{:stratum 0} record! [calls failures stage & _]
  (swap! calls conj stage)
  (when-let [failure (get failures stage)] (throw failure)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} release! [calls failures stage _id]
  (.await (java.util.concurrent.CountDownLatch. 0))
  (record! calls failures stage))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute [calls failures]
  (with-redefs [cleanup/observe-workflow-signal! (partial record! calls failures :observe)
                cleanup/synthesize-patterns! (partial record! calls failures :synthesize)
                cleanup/promote-mature-learnings! (partial record! calls failures :promote)
                environment/release-execution-environment! (partial release! calls failures)]
    (try+ (cleanup/post-workflow-cleanup! {} nil {} nil (acquired))
      (catch Object _ (:throwable &throw-context)))))

(comment ::interruptible-cleanup-fixtures)
