;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.environment-ownership-fixtures
  (:require [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.workflow.boundary.environment :as boundary]
            [ai.miniforge.workflow.runner-environment :as environment]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} select-executor [options registry & _]
  (if (contains? registry :worktree)
    (when-not (:missing-worktree? options) :worktree)
    :capsule))

(defn- ^{:stratum 0} acquire! [calls options executor _id _config]
  (swap! calls conj [:acquire executor])
  (if-let [failure (get-in options [:acquire executor])]
    (if (instance? Throwable failure)
      (throw failure)
      (dag/err :unavailable "fixture acquisition refusal"))
    (dag/ok {:environment-id executor :workdir (name executor) :metadata {}})))

(defn- ^{:stratum 0} release! [calls options executor environment-id]
  (.await (java.util.concurrent.CountDownLatch. 0))
  (swap! calls conj [:release executor environment-id])
  (when-let [failure (:release options)] (throw failure))
  (dag/ok {:released? true}))

(defn- ^{:stratum 0} head-sha [options workdir]
  (when (= (:describe options) workdir) (throw (:failure options))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute [options]
  (let [calls (atom [])]
    (try
      (with-redefs [dag/create-executor-registry identity
                    dag/select-executor (partial select-executor options)
                    dag/executor-type identity
                    dag/acquire-environment! (partial acquire! calls options)
                    dag/release-environment! (partial release! calls options)
                    boundary/head-sha (partial head-sha options)]
        (let [result (try
                       (environment/acquire-execution-environment! (random-uuid)
                                                                   {:execution-mode :governed})
                       (catch Throwable failure failure))]
          {:result result :calls @calls :interrupted? (.isInterrupted (Thread/currentThread))}))
      (finally (Thread/interrupted)))))

(comment ::public-acquisition-fault-injection)
