;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.events
  "Publish phase projections through the shared OPSV audit boundary."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.event-delivery :as delivery]
            [ai.miniforge.phase-opsv.event-artifacts :as artifacts]
            [ai.miniforge.phase-opsv.event-projection :as projection]
            [ai.miniforge.phase-opsv.runtime-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} deliver-next! [ctx stream result event]
  (if (anomaly/anomaly? result) (reduced result) (delivery/emit! ctx stream event)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} emit-phase-events! [ctx phase-key output]
  (when-let [stream (context/stream ctx)]
    (let [projected (projection/phase-events stream (context/workflow-id ctx)
                     (get-in ctx [:execution/input :opsv/evidence-bundle-id]) ctx phase-key output)
          linked (map (partial artifacts/link output) projected)]
      (reduce (partial deliver-next! ctx stream) nil linked))))

(comment
  (emit-phase-events! {} :opsv/plan {}))
