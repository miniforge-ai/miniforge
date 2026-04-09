(ns ai.miniforge.data-foundry.pipeline.dag
  (:require [ai.miniforge.data-foundry.pipeline.messages   :as msg]
            [ai.miniforge.dag-primitives.interface :as dag]))

(defn execution-order
  "Compute topological order of stages using Kahn's algorithm.
   Returns ordered vector of stage IDs, or nil if a cycle is detected."
  [stages]
  (let [dep-map (reduce (fn [m {:stage/keys [id dependencies]}]
                          (assoc m id (set dependencies)))
                        {}
                        stages)
        result  (dag/topological-sort dep-map)]
    (when (dag/ok? result)
      (:data result))))

(defn stages->dag
  "Convert pipeline stages to a DAG task structure.
   Returns {:success? true :dag {:tasks [...] :edges [...]}} or error."
  [stages]
  (let [order (execution-order stages)]
    (if (nil? order)
      {:success? false :errors [(msg/t :pipeline/cycle-detected)]}
      (let [tasks (mapv (fn [{:stage/keys [id name family config]}]
                          {:task/id id
                           :task/name name
                           :task/type :pipeline-stage
                           :task/metadata {:stage/family family
                                           :stage/config config}})
                        stages)
            edges (mapcat (fn [{:stage/keys [id dependencies]}]
                            (map (fn [dep] {:from dep :to id}) dependencies))
                          stages)]
        {:success? true
         :dag {:tasks tasks
               :edges (vec edges)
               :execution-order order}}))))
