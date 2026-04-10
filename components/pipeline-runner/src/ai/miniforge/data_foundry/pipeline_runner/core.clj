(ns ai.miniforge.data-foundry.pipeline-runner.core
  (:require [ai.miniforge.data-foundry.pipeline-runner.messages :as msg]
            [ai.miniforge.schema.interface :as schema])
  (:import [java.util UUID]
           [java.time Instant]))

(def run-statuses
  #{:pending :executing :completed :failed :cancelled})

(defn create-pipeline-run
  "Create a new PipelineRun per N3 §2.4."
  [{:pipeline-run/keys [pipeline-id version mode] :as opts}]
  (let [now (Instant/now)
        run (merge
             {:pipeline-run/id (UUID/randomUUID)
              :pipeline-run/pipeline-id pipeline-id
              :pipeline-run/version version
              :pipeline-run/mode (or mode :full-refresh)
              :pipeline-run/status :pending
              :pipeline-run/input-dataset-versions []
              :pipeline-run/output-dataset-versions []
              :pipeline-run/stage-runs []
              :pipeline-run/connector-cursors {}
              :pipeline-run/policy-evaluations []
              :pipeline-run/created-at now}
             (dissoc opts :pipeline-run/pipeline-id :pipeline-run/version :pipeline-run/mode))]
    (schema/success :pipeline-run run)))

(defn validate-pipeline-run
  [{:pipeline-run/keys [id pipeline-id version mode status]}]
  (let [errors
        (cond-> []
          (nil? id) (conj (msg/t :run/id-required))
          (nil? pipeline-id) (conj (msg/t :run/pipeline-id-required))
          (nil? version) (conj (msg/t :run/version-required))
          (not (contains? #{:full-refresh :incremental :backfill :reprocess} mode))
          (conj (msg/t :run/mode-invalid))
          (not (contains? run-statuses status))
          (conj (msg/t :run/status-invalid)))]
    (if (empty? errors)
      (schema/valid)
      (schema/invalid-with-errors errors))))
