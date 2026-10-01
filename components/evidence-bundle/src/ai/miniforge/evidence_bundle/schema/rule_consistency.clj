;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.rule-consistency
  "Resolution proposals and findings must explain the same selected rules."
  (:require [ai.miniforge.evidence-bundle.schema.gate-packs :as packs]
            [ai.miniforge.schema.interface :as shared]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} known-pack? [pack-ids record]
  (contains? pack-ids (:pack/id record)))

(defn- ^{:stratum 0} enabled-rule-ids [rules]
  (set (map :rule/id (filter :rule/enabled? rules))))

(defn ^{:stratum 0} violation-matches? [gate rules violation]
  (let [rule (get rules (:violation/rule-id violation))]
    (and (= gate (:violation/gate-id violation))
         (:rule/enabled? rule) (:rule/selected? rule)
         (= (:pack/id rule) (:violation/pack-id violation))
         (= (:rule/severity rule) (:violation/severity violation)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} rule-consistent? [pack-ids bound-ids rule]
  (let [proposals (:rule/proposals rule)
        severity (first (sort-by shared/severity-order (map :rule/severity proposals)))
        enabled? (every? :rule/enabled? proposals)]
    (and (seq proposals) (packs/unique-by? :pack/id proposals)
         (known-pack? pack-ids rule) (every? (partial known-pack? bound-ids) proposals)
         (= severity (:rule/severity rule)) (= enabled? (:rule/enabled? rule))
         (or enabled? (not (:rule/selected? rule))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} consistent? [record]
  (let [rules (:gate-execution/resolution-trace record)
        resolved (:gate-execution/resolved-rules record)
        indexed (zipmap (map :rule/id rules) rules)
        pack-ids (set (map :pack/id (:gate-execution/packs record)))
        bound-ids (set (map :pack/id (get-in record [:gate-execution/binding :binding/packs])))
        violations (:gate-execution/violations record)
        gate (:gate-execution/gate-id record)]
    (and (packs/unique-by? :rule/id rules)
         (packs/unique-by? identity resolved)
         (= (set resolved) (enabled-rule-ids rules))
         (packs/unique-by? :violation/id violations)
         (every? (partial rule-consistent? pack-ids bound-ids) rules)
         (every? (partial violation-matches? gate indexed) violations))))
