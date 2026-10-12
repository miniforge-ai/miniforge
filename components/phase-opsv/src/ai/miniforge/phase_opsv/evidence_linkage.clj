;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.evidence-linkage
  "Project acknowledged audit sequences into an N6 workflow-scope link."
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} assembly [ctx]
  (when-let [store (:opsv/evidence-assembly-store ctx)]
    (evidence/get-opsv-assembly store (get-in ctx [:execution/input :opsv/evidence-bundle-id]))))

(defn- ^{:stratum 0} complete-sequences? [record]
  (let [indexed (:opsv/event-sequences record)
        sequences (vals indexed)]
    (and (seq sequences)
         (every? #(contains? indexed %) (:opsv/event-refs record))
         (every? nat-int? sequences)
         (= (count sequences) (count (set sequences)))
         (= (count sequences) (inc (- (apply max sequences) (apply min sequences)))))))

(defn- ^{:stratum 0} workflow-link [record]
  (let [sequences (vals (:opsv/event-sequences record))
        first-sequence (apply min sequences)
        last-sequence (apply max sequences)
        event-count (count sequences)]
    {:event-links/scope-type :workflow
     :event-links/scope-id (:evidence-bundle/workflow-id record)
     :event-links/from-sequence first-sequence
     :event-links/to-sequence last-sequence
     :event-links/event-count event-count}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} links [ctx]
  (let [record (assembly ctx)]
    (when (complete-sequences? record)
      [(workflow-link record)])))

(comment
  (links {}))
