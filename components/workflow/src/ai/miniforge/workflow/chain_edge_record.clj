;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-edge-record
  "Binding-operation records are distinct from child workflow execution outcomes.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} dependencies [step]
  (into {} (filter (comp vector? val)) (:step/input-bindings step)))

(defn ^{:stratum 0} started [id source destination declarations timestamp]
  {:edge/id id :edge/from-workflow-id source :edge/to-workflow-id destination
   :edge/status :running :edge/bindings-count (count declarations) :edge/started-at timestamp})

(defn ^{:stratum 0} identity-fields [edge]
  (select-keys edge [:edge/id :edge/from-workflow-id :edge/to-workflow-id]))

(defn- ^{:stratum 0} predecessor-binding? [record]
  (vector? (:binding/expression record)))

(defn- ^{:stratum 0} sourced-binding [source record]
  (assoc record :binding/source-workflow-id source
                :binding/source-output-path (pr-str (:binding/expression record))))

(defn ^{:stratum 0} failed [edge reason timestamp]
  (assoc edge :edge/status :failed :edge/completed-at timestamp :edge/failure-reason reason))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} completed [edge records timestamp duration]
  (assoc edge :edge/status :completed :edge/completed-at timestamp :edge/duration-ms duration
              :edge/bindings (mapv (partial sourced-binding (:edge/from-workflow-id edge))
                                   (filter predecessor-binding? records))))

(comment
  (dependencies {:step/input-bindings {:upstream [:prev/artifacts] :literal "fixture"}}))
