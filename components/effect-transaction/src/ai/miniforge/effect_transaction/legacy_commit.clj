;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.legacy-commit
  "Compatibility coordinator for caller-supplied grants and zero-argument effects."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.commit :as commit]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invoke-legacy
  [effect-fn _record]
  (effect-fn))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} commit!
  "Reload the proposal, ignore caller usage and retain legacy authority semantics."
  [dir candidate grant-record _usage now effect-fn]
  (let [t (commit/read-proposed dir (:effect/id candidate))]
    (if (anomaly/anomaly? t)
      t
      (commit/commit-record! dir t grant-record now (partial invoke-legacy effect-fn)))))

(comment
  (commit! nil {} nil {} nil identity))
