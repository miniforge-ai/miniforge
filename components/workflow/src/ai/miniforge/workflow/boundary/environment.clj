;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.environment
  "Best-effort environment effects that never hide critical causes."
  (:require [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [ai.miniforge.workflow.messages :as messages]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} preserve-critical! [throwable]
  (when-let [fatal (cleanup/fatal-cause throwable)] (cleanup/propagate! fatal)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publish-persisted! [stream workflow-id data]
  (try+ (events/publish! stream (events/workspace-persisted stream workflow-id data))
    (catch Object _ (preserve-critical! (:throwable &throw-context)) nil)))

(defn ^{:stratum 1} head-sha [working-dir]
  (when working-dir
    (try+
      (let [{:keys [exit out]} (shell/sh "git" "-C" (str working-dir) "rev-parse" "HEAD")]
        (when (zero? exit) (str/trim out)))
      (catch Object _ (preserve-critical! (:throwable &throw-context)) nil))))

(defn ^{:stratum 1} release! [executor environment-id]
  (when (and executor environment-id)
    (try+ (dag/release-environment! executor environment-id)
      (catch Object _
        (let [error (:throwable &throw-context)]
          (preserve-critical! error)
          (println (messages/t :env/release-failed {:error (ex-message error)})))))))

(comment ::environment-effect-boundary)
