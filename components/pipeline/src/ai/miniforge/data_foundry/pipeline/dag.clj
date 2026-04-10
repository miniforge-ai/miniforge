(ns ai.miniforge.data-foundry.pipeline.dag
  (:require [ai.miniforge.data-foundry.pipeline.messages   :as msg]
            [ai.miniforge.dag-primitives.interface :as dag]))

(defn- stage->dep-entry
  "Extract a [stage-id dependencies-set] pair from a stage."
  [{:stage/keys [id dependencies]}]
  [id (set dependencies)])

(defn execution-order
  "Compute topological order of stages using Kahn's algorithm.
   Returns ordered vector of stage IDs, or nil if a cycle is detected."
  [stages]
  (let [dep-map (into {} (map stage->dep-entry) stages)
        result  (dag/topological-sort dep-map)]
    (when (dag/ok? result)
      (:data result))))

(defn- stage->task
  "Convert a pipeline stage to a DAG task."
  [{:stage/keys [id name family config]}]
  {:task/id id
   :task/name name
   :task/type :pipeline-stage
   :task/metadata {:stage/family family
                   :stage/config config}})

(defn- stage->edges
  "Extract dependency edges from a stage."
  [{:stage/keys [id dependencies]}]
  (mapv (fn [dep] {:from dep :to id}) dependencies))

(defn stages->dag
  "Convert pipeline stages to a DAG task structure.
   Returns {:success? true :dag {:tasks [...] :edges [...]}} or error."
  [stages]
  (let [order (execution-order stages)]
    (if (nil? order)
      {:success? false :errors [(msg/t :pipeline/cycle-detected)]}
      {:success? true
       :dag {:tasks (mapv stage->task stages)
             :edges (vec (mapcat stage->edges stages))
             :execution-order order}})))
