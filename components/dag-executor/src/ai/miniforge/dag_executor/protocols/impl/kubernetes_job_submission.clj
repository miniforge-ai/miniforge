;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-job-submission
  "Bound create-only native input and output by the caller's absolute deadline."
  (:require [ai.miniforge.dag-executor.protocols.impl.kubernetes-defaults :as defaults]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-capture :as capture]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [cheshire.core :as json])
  (:import [java.nio.charset StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} arguments [executable job timeout-ms]
  [(or executable (:executable (defaults/data)))
   "create" "-n" (get-in job [:metadata :namespace]) "-f" "-" "-o" "json"
   (str "--request-timeout=" timeout-ms "ms")])

(defn- ^{:stratum 0} payload [job]
  (.getBytes ^String (json/generate-string job) StandardCharsets/UTF_8))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} submit! [until executable job timeout-ms]
  (cond
    (.isInterrupted (Thread/currentThread)) (capture/failure :interrupted)
    (deadline/expired? until) (capture/failure :deadline)
    :else (command/run! until (arguments executable job timeout-ms) (payload job))))

(comment (submit! (System/nanoTime) nil nil 1))
