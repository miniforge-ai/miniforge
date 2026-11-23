;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.cleanup-attempt
  "Report ordinary observer failures while preserving critical causes."
  (:require [ai.miniforge.resource-cleanup.interface :as cleanup]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} attempt! [operation report-failure]
  (try+ (operation)
    (catch Object _
      (let [throwable (:throwable &throw-context)]
        (when-let [fatal (cleanup/fatal-cause throwable)] (cleanup/propagate! fatal))
        (report-failure throwable)))))

(comment ::cleanup-observer-boundary)
