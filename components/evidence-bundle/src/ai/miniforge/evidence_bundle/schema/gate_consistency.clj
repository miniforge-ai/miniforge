;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.gate-consistency
  "Cross-record joins for structurally validated gate evidence."
  (:require [ai.miniforge.evidence-bundle.schema.gate-packs :as gate-packs]
            [ai.miniforge.evidence-bundle.schema.gate-waivers :as waivers]
            [ai.miniforge.evidence-bundle.schema.rule-consistency :as rules]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} consistent? [record]
  (let [gate (:gate-execution/gate-id record)
        binding (:gate-execution/binding record)
        bound (:binding/packs binding)
        outcome (:gate-execution/outcome record)]
    (and (= gate (:gate/id binding))
         (gate-packs/consistent? (:binding/packs binding) (:gate-execution/packs record))
         (or (= :failed outcome) (seq bound))
         (rules/consistent? record)
         (waivers/consistent? record))))
