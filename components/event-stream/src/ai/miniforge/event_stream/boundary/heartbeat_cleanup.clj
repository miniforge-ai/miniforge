;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.heartbeat-cleanup
  "Run owned cleanup without masking primary, fatal or structured failures."
  (:require [ai.miniforge.resource-cleanup.interface :as cleanup]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} after-failure! cleanup/after-failure!)

(def ^{:stratum 0} finish! cleanup/finish!)

(comment ::cleanup-failure-priority)
