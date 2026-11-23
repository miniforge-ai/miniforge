;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-pipeline
  "Workflow entry, target and duplicate-phase diagnostics."
  (:require [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.state-identity :refer [first-phase-index]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} duplicate-phase?
  [[_phase count]]
  (> count 1))

(defn ^{:stratum 0} unresolved-target-error
  [error-key phase target]
  {:error error-key
   :phase phase
   :target target
   :message (messages/t error-key
                        {:phase phase
                         :target target})})

(defn ^{:stratum 0} duplicate-phase-warning
  [duplicate-phases]
  {:warning :duplicate-phase-identifiers
   :phases duplicate-phases
   :message (messages/t :status/duplicate-phases)})

(defn ^{:stratum 0} empty-pipeline-error
  []
  {:error :empty-pipeline
   :message (messages/t :status/empty-pipeline)})

(defn ^{:stratum 0} unknown-entry-phase-error
  [entry-phase]
  {:error :unknown-entry-phase
   :entry-phase entry-phase
   :message (messages/t :status/unknown-entry-phase
                        {:entry-phase entry-phase})})

(defn ^{:stratum 0} unreachable-phase-error
  [phases]
  {:error :unreachable-phase-states
   :phases phases
   :message (messages/t :status/unreachable-machine-phases
                        {:phases phases})})

(defn ^{:stratum 0} unreachable-state-error
  [states]
  {:error :unreachable-machine-states
   :states states
   :message (messages/t :status/unreachable-machine-states
                        {:states states})})

(defn ^{:stratum 0} bad-entry-phase [workflow]
  (let [entry (or (:workflow/entry workflow) (:workflow/entry-phase workflow))
        phases (:workflow/phases workflow)
        phase-ids (set (map :phase/id phases))]
    (when (and entry phases (not (contains? phase-ids entry))) entry)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} unknown-success-target
  [pipeline {:keys [phase on-success]}]
  (when (and on-success
             (nil? (first-phase-index pipeline on-success)))
    (unresolved-target-error :unknown-on-success-target phase on-success)))

(defn ^{:stratum 1} unknown-fail-target
  [pipeline {:keys [phase on-fail]}]
  (when (and on-fail
             (nil? (first-phase-index pipeline on-fail)))
    (unresolved-target-error :unknown-on-fail-target phase on-fail)))

(defn ^{:stratum 1} duplicate-phases [pipeline]
  (->> pipeline (map :phase) frequencies (filter duplicate-phase?) (mapv first)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} errors [workflow pipeline]
  (let [entry (bad-entry-phase workflow)]
    (vec (concat
          (when (empty? pipeline) [(empty-pipeline-error)])
          (when entry [(unknown-entry-phase-error entry)])
          (keep (partial unknown-success-target pipeline) pipeline)
          (keep (partial unknown-fail-target pipeline) pipeline)))))

(defn ^{:stratum 2} warnings [pipeline]
  (let [duplicates (duplicate-phases pipeline)]
    (if (seq duplicates) [(duplicate-phase-warning duplicates)] [])))

(comment ::pipeline-diagnostics)
