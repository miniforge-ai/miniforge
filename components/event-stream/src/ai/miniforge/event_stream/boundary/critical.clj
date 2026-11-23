;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.critical
  "Preserve fatal and interruption causes across storage and delivery boundaries."
  (:require [ai.miniforge.resource-cleanup.interface :as cleanup]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} cause cleanup/fatal-cause)

(def ^{:stratum 0} propagate! cleanup/propagate!)

(comment
  (cause nil))
