;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.finalization-events
  "Retain acknowledged material across the event-to-finalization boundary."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.events :as events]
            [ai.miniforge.phase-opsv.finalization :as finalization]
            [ai.miniforge.phase-opsv.publication-boundary :as publication]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} finalize-published-events! [ctx output]
  (let [published (events/emit-phase-events! ctx :opsv/actuate output)]
    (if (anomaly/any-anomaly? published)
      (assoc-in published [:anomaly/data :opsv/phase-output] output)
      (finalization/finalize! ctx output))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} finalize-after-events! [ctx output]
  (publication/call-with-exception-handling output #(finalize-published-events! ctx output)))
