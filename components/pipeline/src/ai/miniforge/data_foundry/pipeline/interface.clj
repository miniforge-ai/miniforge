(ns ai.miniforge.data-foundry.pipeline.interface
  (:require [ai.miniforge.data-foundry.pipeline.core :as core]
            [ai.miniforge.data-foundry.pipeline.stage :as stage]
            [ai.miniforge.data-foundry.pipeline.dag :as dag]))

;; -- Stage Families --
(def stage-families stage/stage-families)

;; -- Execution Modes --
(def execution-modes core/execution-modes)

;; -- Pipeline CRUD --
(defn create-pipeline
  "Create a pipeline definition.
   Required: :pipeline/name, :pipeline/version, :pipeline/stages, :pipeline/mode,
             :pipeline/input-datasets, :pipeline/output-datasets
   Returns {:success? true :pipeline ...} or {:success? false :errors [...]}"
  [opts]
  (core/create-pipeline opts))

(defn validate-pipeline
  "Validate a pipeline definition against N3 §2.
   Checks stage dependencies, connector refs, input/output consistency."
  [pipeline]
  (core/validate-pipeline pipeline))

;; -- Stage Operations --
(defn create-stage
  "Create a stage definition.
   Required: :stage/name, :stage/family, :stage/input-datasets, :stage/output-datasets, :stage/dependencies"
  [opts]
  (stage/create-stage opts))

(defn validate-stage
  "Validate a stage definition against N3 §2.2."
  [stage-def]
  (stage/validate-stage stage-def))

;; -- DAG Conversion --
(defn stages->dag
  "Convert pipeline stages to a DAG task structure for Core dag-executor.
   Returns {:success? true :dag ...} or {:success? false :errors [...]}"
  [stages]
  (dag/stages->dag stages))

(defn execution-order
  "Compute topological execution order for pipeline stages.
   Returns ordered vector of stage IDs."
  [stages]
  (dag/execution-order stages))
