;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.heartbeat-cleanup
  "Run owned cleanup without masking primary, fatal or structured failures."
  (:require [ai.miniforge.event-stream.boundary.critical :as critical]
            [ai.miniforge.event-stream.boundary.throwable-graph :as graph]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} capture [operation]
  (try+ (operation) nil
    (catch Object _ (:throwable &throw-context))))

(defn- ^{:stratum 0} preferred [primary cleanup]
  (or (critical/cause primary) (critical/cause cleanup) primary cleanup))

(defn- ^{:stratum 0} suppress! [failure other]
  (when (and other (not (graph/reaches? other failure)))
    (.addSuppressed ^Throwable failure other)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} propagate! [primary cleanup]
  (when-let [failure (preferred primary cleanup)]
    (suppress! failure primary)
    (suppress! failure cleanup)
    (critical/propagate! failure)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} after-failure! [failure cleanup]
  (propagate! failure (capture cleanup)))

(defn ^{:stratum 2} finish! [operation cleanup]
  (let [primary (capture operation)
        secondary (capture cleanup)]
    (propagate! primary secondary)))

(comment ::cleanup-failure-priority)
