(ns ai.miniforge.data-foundry.pipeline-runner.interface
  (:require [ai.miniforge.data-foundry.pipeline-runner.core :as core]
            [ai.miniforge.data-foundry.pipeline-runner.run :as run]
            [ai.miniforge.data-foundry.pipeline-runner.transforms :as transforms]))

;; -- Pipeline Run Status --
(def run-statuses
  "N3 §2.4 pipeline run statuses"
  #{:pending :executing :completed :failed :cancelled})

;; -- Pipeline Execution --
(defn create-pipeline-run
  "Create a new PipelineRun record.
   Required: :pipeline-run/pipeline-id, :pipeline-run/version, :pipeline-run/mode
   Returns schema/success with :pipeline-run key."
  [opts]
  (core/create-pipeline-run opts))

(defn execute-pipeline
  "Execute a pipeline in full-refresh mode.
   Takes a pipeline definition, a map of connector implementations (keyed by connector-ref UUID),
   and an optional context map.
   Returns schema/success or schema/failure with :pipeline-run key."
  [pipeline connectors context]
  (run/execute-pipeline pipeline connectors context))

(defn validate-pipeline-run
  "Validate a PipelineRun record."
  [pipeline-run]
  (core/validate-pipeline-run pipeline-run))

;; -- Built-in Transforms --

(def built-in-transforms
  "Map of built-in transform type keywords to transform functions.
   Register these in the pipeline execution context under :transforms."
  {:derived-ratio transforms/derived-ratio})
