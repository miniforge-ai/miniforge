;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-validation
  "Validate topology references before compiling executable guards and actions."
  (:require [ai.miniforge.workflow.actions :as actions]
            [ai.miniforge.workflow.definition :as definition]
            [ai.miniforge.workflow.guards :as guards]
            [ai.miniforge.workflow.state-compiler :as compiler]
            [ai.miniforge.workflow.state-pipeline :as pipeline]
            [ai.miniforge.workflow.state-reachability :as reachability]
            [ai.miniforge.workflow.state-structure :as structure]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reference-errors [states]
  (filterv some? [(guards/validate-guard-references states)
                 (actions/validate-action-references states)]))

(defn- ^{:stratum 0} unreachable-phase [unreachable {:keys [phase state-id]}]
  (when (contains? unreachable state-id) phase))

(defn- ^{:stratum 0} validation-result [errors warnings machine]
  {:valid? (empty? errors)
   :errors errors
   :warnings warnings
   :machine (when (empty? errors) machine)})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} reachability-errors [machine]
  (let [unreachable (reachability/unreachable-states machine)
        phases (into [] (keep (partial unreachable-phase unreachable)) (:workflow/phase-entries machine))]
    (vec (concat
          (when (seq phases) [(pipeline/unreachable-phase-error phases)])
          (when (seq unreachable) [(pipeline/unreachable-state-error (vec unreachable))])))))

(defn- ^{:stratum 1} inspect-topology [raw]
  (let [states (:states raw)
        refs (reference-errors states)
        structural (structure/structural-invariant-errors states)
        machine (when (empty? refs) (compiler/compile-topology raw))]
    {:errors (into refs structural) :machine machine}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} validate [workflow build-raw]
  (let [normalized (definition/ensure-execution-pipeline workflow)
        phases (definition/execution-pipeline normalized)
        initial-errors (pipeline/errors workflow phases)
        warnings (pipeline/warnings phases)
        inspection (when (empty? initial-errors) (inspect-topology (build-raw normalized)))
        machine (:machine inspection)
        reach-errors (when machine (reachability-errors machine))
        errors (vec (concat initial-errors (:errors inspection) reach-errors))]
    (validation-result errors warnings machine)))

(comment ::validate-before-resolution)
