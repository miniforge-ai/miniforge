;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-composition
  "Detached definition values and invocation records for sequential chains.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} unique-step-ids? [definition]
  (let [ids (mapv :step/id (:chain/steps definition))]
    (= (count ids) (count (set ids)))))

(defn- ^{:stratum 0} binding-entry [[target value]]
  [target (if (vector? value) (into [] value) value)])

(defn- ^{:stratum 0} snapshot [definition]
  (cond-> {:chain/definition-id (:chain/id definition)
           :chain/definition-version (:chain/version definition)
           :chain/steps (:chain/steps definition)}
    (contains? definition :chain/description)
    (assoc :chain/description (:chain/description definition))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} copy-step [step]
  (if (map? step)
    (let [value (into {} step)
          bindings (:step/input-bindings value)]
      (cond-> value
        (map? bindings) (assoc :step/input-bindings (into {} (map binding-entry) bindings))))
    step))

(defn ^{:stratum 1} run-record [definition]
  (let [composition (snapshot definition)
        identity (select-keys composition [:chain/definition-id :chain/definition-version])]
    (assoc identity
           :chain/run-id (random-uuid)
           :chain/definition-snapshot composition)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} detach [definition]
  (if (map? definition)
    (let [value (into {} definition)
          steps (:chain/steps value)]
      (cond-> value
        (vector? steps) (assoc :chain/steps (mapv copy-step steps))))
    definition))

(comment
  (unique-step-ids? {:chain/steps []}))
