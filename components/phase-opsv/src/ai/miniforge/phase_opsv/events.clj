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

(defn- ^{:stratum 0} project-phase-events [ctx phase-key output]
  (let [projected (projection/phase-events (context/stream ctx) (context/workflow-id ctx)
                   (get-in ctx [:execution/input :opsv/evidence-bundle-id]) ctx phase-key output)]
    (mapv (partial artifacts/link output) projected)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} retain-actuation-events [ctx output]
  ;; Recovery reuses occurrence timestamps and any legacy reserved positions.
  (assoc output :opsv/actuation-events (project-phase-events ctx :opsv/actuate output)))

(defn- ^{:stratum 1} phase-events [ctx phase-key output]
  (if (and (= :opsv/actuate phase-key)
           (contains? (:execution/opts ctx) :opsv/evidence-base)
           (contains? output :opsv/actuation-events))
    (:opsv/actuation-events output)
    (project-phase-events ctx phase-key output)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} emit-phase-events! [ctx phase-key output]
  (when-let [stream (context/stream ctx)]
    (reduce (partial deliver-next! ctx stream) nil (phase-events ctx phase-key output))))

(comment
  (emit-phase-events! {} :opsv/plan {}))
