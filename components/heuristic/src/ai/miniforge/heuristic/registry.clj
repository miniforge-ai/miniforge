;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.heuristic.registry
  "Enhanced heuristic registry with traffic splitting and metrics tracking.
   Per learning.spec §4.3.

   Layer 0: Registry operations
   Layer 1: Traffic-aware selection"
  (:require
   [ai.miniforge.heuristic.core :as core]
   [ai.miniforge.heuristic.lifecycle :as lifecycle]))

;------------------------------------------------------------------------------ Layer 0
;; Metadata management

(defn get-heuristic-with-metadata
  "Get a heuristic with its full metadata (status, traffic, metrics)."
  [heuristic-type version opts]
  (core/get-heuristic heuristic-type version opts))

(defn update-metadata!
  "Update metadata fields on a stored heuristic."
  [heuristic-type version updates opts]
  (when-let [current (core/get-heuristic heuristic-type version opts)]
    (let [updated (merge current updates)]
      (core/save-heuristic heuristic-type version updated opts)
      updated)))

(defn set-status!
  "Transition heuristic to a new lifecycle status.
   Returns updated heuristic or nil if transition is invalid."
  [heuristic-type version new-status opts]
  (when-let [current (core/get-heuristic heuristic-type version opts)]
    (let [current-status (get current :heuristic/status :draft)]
      (when (lifecycle/valid-transition? current-status new-status)
        (update-metadata! heuristic-type version
                          {:heuristic/status new-status
                           :heuristic/status-changed-at (java.time.Instant/now)}
                          opts)))))

(defn set-traffic!
  "Set the traffic allocation for a heuristic version (0.0 - 1.0)."
  [heuristic-type version traffic-pct opts]
  (update-metadata! heuristic-type version
                    {:heuristic/traffic traffic-pct}
                    opts))

(defn record-metrics!
  "Record metrics for a heuristic version."
  [heuristic-type version metrics opts]
  (when-let [current (core/get-heuristic heuristic-type version opts)]
    (let [existing-metrics (get current :heuristic/metrics {})
          updated (merge existing-metrics metrics)]
      (update-metadata! heuristic-type version
                        {:heuristic/metrics updated}
                        opts))))

;------------------------------------------------------------------------------ Layer 1
;; Traffic-aware selection

(defn select-heuristic
  "Select which heuristic version to use, respecting traffic splits.

   If a canary version exists with traffic allocation, randomly route
   to it based on the allocation percentage. Otherwise return active.

   Arguments:
     heuristic-type - keyword
     opts           - {:store store-instance}

   Returns: heuristic data map or nil"
  [heuristic-type opts]
  (let [all-versions (core/list-versions heuristic-type opts)
        all-heuristics (->> all-versions
                            (map #(core/get-heuristic heuristic-type % opts))
                            (filter some?))
        canary (first (filter #(= :canary (:heuristic/status %)) all-heuristics))
        active (core/get-active-heuristic heuristic-type opts)]
    (cond
      ;; Canary with traffic split
      (and canary (:heuristic/traffic canary))
      (if (< (rand) (:heuristic/traffic canary))
        canary
        (or active canary))

      ;; Active version
      active active

      ;; Fall back to newest
      (first all-heuristics) (first all-heuristics)

      :else nil)))
