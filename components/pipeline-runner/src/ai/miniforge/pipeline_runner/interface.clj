(ns ai.miniforge.pipeline-runner.interface
  (:require [ai.miniforge.pipeline-runner.core :as core]
            [ai.miniforge.pipeline-runner.preflight :as preflight]
            [ai.miniforge.pipeline-runner.run :as run]
            [ai.miniforge.pipeline-runner.transforms :as transforms]))

;------------------------------------------------------------------------------ Layer 0

;; -- Pipeline Run Status --
(def ^{:stratum 0} run-statuses
  "N3 §2.4 pipeline run statuses"
  #{:pending :executing :completed :failed :cancelled})

;; -- Pipeline Execution --
(defn ^{:stratum 0} create-pipeline-run
  "Create a new PipelineRun record.
   Required: :pipeline-run/pipeline-id, :pipeline-run/version, :pipeline-run/mode
   Returns schema/success with :pipeline-run key."
  [opts]
  (core/create-pipeline-run opts))

(defn ^{:stratum 0} execute-pipeline
  "Execute a pipeline in full-refresh mode.
   Takes a pipeline definition, a map of connector implementations (keyed by connector-ref UUID),
   and an optional context map.
   Returns schema/success or schema/failure with :pipeline-run key."
  [pipeline connectors context]
  (run/execute-pipeline pipeline connectors context))

(defn ^{:stratum 0} preflight
  "Check, without opening any connector, that every connector, transform,
   quality rule and spawn function a pipeline names resolves. A stage that is
   neither :ingest nor :publish and skips processing on purpose declares
   :stage/pass-through? true in its config.
   Returns schema/success with :preflight, or schema/failure whose
   :preflight/problems names each stage id, :preflight/problem kind and the
   missing :preflight/ref."
  [pipeline connectors context]
  (preflight/check pipeline connectors context))

(defn ^{:stratum 0} validate-pipeline-run
  "Validate a PipelineRun record."
  [pipeline-run]
  (core/validate-pipeline-run pipeline-run))

;; -- Built-in Transforms --
(def ^{:stratum 0} built-in-transforms
  "Map of built-in transform type keywords to transform functions.
   Register these in the pipeline execution context under :transforms."
  {:derived-ratio transforms/derived-ratio})
