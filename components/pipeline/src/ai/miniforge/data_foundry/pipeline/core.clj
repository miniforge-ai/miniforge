(ns ai.miniforge.data-foundry.pipeline.core
  (:require [ai.miniforge.data-foundry.pipeline.stage :as stage]
            [ai.miniforge.data-foundry.pipeline.dag :as dag]
            [ai.miniforge.data-foundry.pipeline.messages :as msg])
  (:import [java.util UUID]
           [java.time Instant]))

(def execution-modes
  "N3 §3 execution modes"
  #{:full-refresh :incremental :backfill :reprocess})

(defn- validate-pipeline-impl
  [{:pipeline/keys [id name version stages mode input-datasets output-datasets]}]
  (let [base-errors
        (cond-> []
          (nil? id)
          (conj (msg/t :pipeline/id-required))

          (not (string? name))
          (conj (msg/t :pipeline/name-must-be-string))

          (not (string? version))
          (conj (msg/t :pipeline/version-must-be-string))

          (not (vector? stages))
          (conj (msg/t :pipeline/stages-must-be-vector))

          (and (vector? stages) (empty? stages))
          (conj (msg/t :pipeline/stages-must-not-be-empty))

          (not (contains? execution-modes mode))
          (conj (msg/t :pipeline/mode-invalid {:allowed execution-modes}))

          (not (vector? input-datasets))
          (conj (msg/t :pipeline/input-ds-must-be-vector))

          (not (vector? output-datasets))
          (conj (msg/t :pipeline/output-ds-must-be-vector)))

        ;; Validate individual stages
        stage-errors
        (when (and (vector? stages) (seq stages))
          (mapcat
           (fn [s]
             (let [v (stage/validate-stage s)]
               (when-not (:success? v)
                 (map #(msg/t :pipeline/stage-error {:name (:stage/name s) :error %})
                      (:errors v)))))
           stages))

        ;; Validate DAG (no cycles, all deps exist)
        dag-errors
        (when (and (vector? stages) (seq stages))
          (let [stage-ids (set (map :stage/id stages))]
            (mapcat
             (fn [s]
               (for [dep (:stage/dependencies s)
                     :when (not (contains? stage-ids dep))]
                 (msg/t :pipeline/unknown-dependency {:name (:stage/name s) :dep dep})))
             stages)))

        ;; Check for cycles
        cycle-errors
        (when (and (vector? stages) (seq stages))
          (let [order-result (dag/execution-order stages)]
            (when (nil? order-result)
              [(msg/t :pipeline/cycle-detected)])))]

    (vec (concat base-errors stage-errors dag-errors cycle-errors))))

(defn validate-pipeline
  [pipeline]
  (let [errors (validate-pipeline-impl pipeline)]
    (if (empty? errors)
      {:success? true}
      {:success? false :errors errors})))

(defn create-pipeline
  [opts]
  (let [now (Instant/now)
        pipeline (cond-> opts
                   (nil? (:pipeline/id opts))
                   (assoc :pipeline/id (UUID/randomUUID))

                   (nil? (:pipeline/created-at opts))
                   (assoc :pipeline/created-at now)

                   (nil? (:pipeline/updated-at opts))
                   (assoc :pipeline/updated-at now))
        validation (validate-pipeline pipeline)]
    (if (:success? validation)
      {:success? true :pipeline pipeline}
      {:success? false :errors (:errors validation)})))
