;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.web-dashboard.server.boundary.startup
  "Legacy HTTP startup boundary: roll back resources before propagating library failures."
  (:require [ai.miniforge.web-dashboard.server.shutdown :as shutdown]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} acquire!
  "Preserve the throwing HTTP startup API after releasing acquired resources."
  [start!]
  (let [resources (atom {})]
    (try
      (start! resources)
      (catch Throwable failure
        (try
          (shutdown/stop! @resources)
          (catch Throwable cleanup-failure
            (when-not (identical? failure cleanup-failure)
              (.addSuppressed failure cleanup-failure))))
        (throw failure)))))
