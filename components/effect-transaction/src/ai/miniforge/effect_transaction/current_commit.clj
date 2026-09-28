;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.current-commit
  "Commit a durable effect using runtime ports, not caller snapshots."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.commit :as commit]
            [ai.miniforge.effect-transaction.runtime-boundary :as runtime]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} commit-at-current-time!
  [dir t grant-record clock effect-fn]
  (let [now (runtime/current-time clock)]
    (if (anomaly/anomaly? now)
      now
      (commit/commit-record! dir t grant-record now effect-fn))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} commit-with-current-grant!
  [dir t lookup-grant clock effect-fn]
  (let [current (runtime/current-grant lookup-grant (:effect/grant-id t))]
    (if (anomaly/anomaly? current)
      current
      (commit-at-current-time! dir t current clock effect-fn))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} commit!
  [dir id lookup-grant clock effect-fn]
  (let [t (commit/read-proposed dir id)]
    (if (anomaly/anomaly? t)
      t
      (commit-with-current-grant! dir t lookup-grant clock effect-fn))))

(comment
  (commit! nil nil identity identity identity))
