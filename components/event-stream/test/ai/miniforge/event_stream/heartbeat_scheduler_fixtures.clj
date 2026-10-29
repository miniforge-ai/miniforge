;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-scheduler-fixtures
  (:require [slingshot.slingshot :refer [try+ throw+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} structured-failure []
  (try+ (throw+ {:fixture/failure :structured})
    (catch Object _ (:throwable &throw-context))))

(comment ::structured-executor-failure)
