;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.verification-adapter
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.exception-policy :as exception-policy]
            [ai.miniforge.phase-opsv.messages :as msg]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} invoke [operation adapter request]
  ;; Both acquisition and measurement preserve fatal errors and cancellation.
  (try+
    (operation adapter request)
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch exception-policy/nonfatal? _
      (anomaly/anomaly :unavailable (msg/ts :verification/failed) {}))))

(comment ::adapter-exception-boundary)
