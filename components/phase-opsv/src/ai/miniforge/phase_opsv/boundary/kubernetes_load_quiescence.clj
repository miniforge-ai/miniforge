;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-quiescence
  "API surface class 1: read-only quiescence classification after fresh ownership."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-ownership :as ownership]
            [ai.miniforge.phase-opsv.kubernetes-load-quiescence :as quiescence]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} read! [runtime plan]
  (anomaly/let-ok [owned (ownership/read! runtime plan)]
    (assoc owned :load/quiescence (quiescence/outcome owned))))

(comment ::absence-failed-phase-delete-acknowledgement-and-deadline-expiry-are-not-termination)
