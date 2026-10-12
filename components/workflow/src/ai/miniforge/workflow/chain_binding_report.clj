;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-binding-report
  "Resolution evidence for deployed optional bindings; no edge or execution authority."
  (:require [ai.miniforge.workflow.chain-bindings :as bindings]
            [ai.miniforge.workflow.chain-binding-evidence :as evidence]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} binding-record [inputs [target expression]]
  (let [resolved? (contains? inputs target)]
    (evidence/redact
     (cond-> {:binding/target-input (subs (str target) 1)
             :binding/expression expression
             :binding/required? false
             :binding/resolved? resolved?}
       resolved? (assoc :binding/value (get inputs target))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-inputs [declarations previous input]
  (let [inputs (bindings/resolve-bindings declarations previous input)]
    {:inputs inputs :bindings (mapv (partial binding-record inputs) declarations)}))

(comment
  (resolve-inputs {:flag :flag} nil {:flag false}))
