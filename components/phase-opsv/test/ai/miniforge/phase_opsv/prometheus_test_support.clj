;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-test-support
  "Explicit settings and failure fixtures, without transport or network access."
  (:require [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} settings
  ([] (settings {}))
  ([overrides] (merge {:prometheus/base-url "https://metrics.invalid/prometheus/"} overrides)))

(defn ^{:stratum 0} throw-failure! [failure & _args] (throw failure))

(defn ^{:stratum 0} ordinary-failure [cause] (Exception. "secret configuration failure" cause))

(defn ^{:stratum 0} structured-failure [cause]
  (ex-info "secret configuration failure" {:config/resource "secret-path"} cause))

(defn ^{:stratum 0} interrupted-result [operation]
  (try+ (operation) nil
    (catch Object _
      [(:throwable &throw-context) (.isInterrupted (Thread/currentThread))])))

(comment ::explicit-prometheus-settings-fixtures)
