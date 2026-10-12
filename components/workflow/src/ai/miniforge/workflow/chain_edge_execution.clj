;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-edge-execution
  "Acknowledge predecessor binding before loading the downstream child."
  (:require [ai.miniforge.clock.interface :as clock]
            [ai.miniforge.workflow.boundary.chain-step :as child]
            [ai.miniforge.workflow.chain-edge-outcome :as terminal]
            [ai.miniforge.workflow.chain-edge-publication :as publication]
            [ai.miniforge.workflow.chain-edge-record :as record]
            [ai.miniforge.workflow.chain-outcome :as outcome]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} prepare-edge [step opts]
  (let [declarations (record/dependencies step)
        source (:chain/upstream-workflow-id opts)]
    (when (seq declarations)
      (record/started (random-uuid) source (:workflow-id opts) declarations (java.util.Date. (clock/now-ms))))))

(defn- ^{:stratum 0} resolve-started! [step previous input opts edge]
  (let [report (child/prepare step previous input)]
    (if (outcome/failed? report) (terminal/failed! opts edge report)
        (terminal/completed! opts edge report))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} candidate-edge [step opts]
  (when (and (:chain/event-stream opts) (:chain/upstream-workflow-id opts))
    (prepare-edge step opts)))

(defn- ^{:stratum 1} execute! [step previous input opts edge]
  (let [receipt (publication/started! opts edge)]
    (if (outcome/failure-value? receipt) (terminal/start-failed! opts edge receipt)
        (resolve-started! step previous input opts edge))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare! [step previous input opts]
  (if-let [edge (candidate-edge step opts)]
    (execute! step previous input opts edge)
    (child/prepare step previous input)))

(comment
  ::acknowledged-binding-resolution)
