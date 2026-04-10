;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.evaluation.interface
  "Public API for the evaluation pipeline per N1 §3.3.3.

   Layer 0: Golden set management
   Layer 1: Statistical comparison
   Layer 2: Golden set evaluation"
  (:require
   [ai.miniforge.evaluation.golden-set :as golden]
   [ai.miniforge.evaluation.comparator :as comparator]))

;------------------------------------------------------------------------------ Layer 0
;; Golden sets

(def create-golden-set golden/create-golden-set)
(def add-entry golden/add-entry)
(def entry-count golden/entry-count)

;------------------------------------------------------------------------------ Layer 1
;; Statistical comparison

(def compare-results
  "Compare baseline vs candidate metric distributions.
   Returns: {:significant? :p-value :lift :recommendation}"
  comparator/compare-results)

;------------------------------------------------------------------------------ Layer 2
;; Golden set evaluation

(def evaluate-entry golden/evaluate-entry)
(def run-golden-set
  "Run golden set: execute each entry, compare against criteria.
   Returns: {:total :passed :failed :pass-rate :results}"
  golden/run-golden-set)
