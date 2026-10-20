;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-binding-report
  "Resolution evidence and explicit required-input policy; no execution authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.chain-binding-evidence :as evidence]
            [ai.miniforge.workflow.chain-bindings :as bindings]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} binding-record [inputs [target declaration]]
  (let [resolved? (contains? inputs target)]
    (evidence/redact
     (cond-> {:binding/target-input (subs (str target) 1)
              :binding/expression (bindings/expression declaration)
              :binding/required? (bindings/required? declaration)
              :binding/resolved? resolved?}
       resolved? (assoc :binding/value (get inputs target))))))

(defn- ^{:stratum 0} missing-required? [record]
  (and (:binding/required? record) (not (:binding/resolved? record))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-inputs [declarations previous input]
  (let [inputs (bindings/resolve-bindings declarations previous input)
        records (mapv (partial binding-record inputs) declarations)
        report {:inputs inputs :bindings records}]
    (if (some missing-required? records)
      (anomaly/anomaly :invalid-input (messages/t :chain/required-binding-unresolved) {:bindings records})
      report)))

(comment
  (resolve-inputs {:flag :flag} nil {:flag false}))
