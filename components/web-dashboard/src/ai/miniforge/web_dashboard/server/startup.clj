;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.web-dashboard.server.startup
  "Roll back acquired HTTP, watcher and listener resources when startup fails."
  (:require [ai.miniforge.web-dashboard.server.shutdown :as shutdown]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} acquire! [start!]
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
