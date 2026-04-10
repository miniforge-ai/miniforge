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

(def create-golden-set
  "Create a golden set from curated entries.
   Options: {:id :entries :version}"
  golden/create-golden-set)

(def add-entry
  "Add an entry to a golden set. Returns updated golden set."
  golden/add-entry)

(def entry-count
  "Get the number of entries in a golden set."
  golden/entry-count)

;------------------------------------------------------------------------------ Layer 1
;; Statistical comparison

(def compare-results
  "Compare baseline vs candidate metric distributions.
   Returns: {:significant? :p-value :lift :recommendation}"
  comparator/compare-results)

;------------------------------------------------------------------------------ Layer 2
;; Golden set evaluation

(def evaluate-entry
  "Evaluate a single golden set entry against actual output."
  golden/evaluate-entry)

(def run-golden-set
  "Run golden set: execute each entry, compare against criteria.
   Returns: {:total :passed :failed :pass-rate :results}"
  golden/run-golden-set)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def gs (-> (create-golden-set {:id "test" :version "1.0.0"})
              (add-entry {:entry/id "e1" :entry/input {} :entry/pass-criteria [:ok]})))
  (entry-count gs) ;; => 1

  (compare-results (repeat 20 0.8) (repeat 20 0.9))

  :leave-this-here)
