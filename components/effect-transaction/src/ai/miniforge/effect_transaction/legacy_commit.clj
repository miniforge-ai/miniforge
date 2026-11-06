;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.legacy-commit
  "Compatibility coordinator for caller-supplied grants and zero-argument effects."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.commit :as commit]
            [ai.miniforge.execution-grant.interface :as grant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invoke-legacy
  [effect-fn _record]
  (effect-fn))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} commit-loaded! [dir t grant-record now effect-fn]
  (if (grant/experiment-authority-required? (:effect/class t) (:effect/proposal t))
    (commit/deny! dir t (grant/authorize nil {} now) now)
    (commit/commit-record! dir t grant-record now (partial invoke-legacy effect-fn))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} commit!
  "Reload the proposal; experiment effects require current authority, never legacy snapshots."
  [dir candidate grant-record _usage now effect-fn]
  (let [t (commit/read-proposed dir (:effect/id candidate))]
    (if (anomaly/anomaly? t)
      t
      (commit-loaded! dir t grant-record now effect-fn))))

(comment
  (commit! nil {} nil {} nil identity))
