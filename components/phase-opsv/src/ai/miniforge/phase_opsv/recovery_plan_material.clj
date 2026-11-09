;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.recovery-plan-material
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.protected-material :as protected]
            [ai.miniforge.phase-opsv.recovery-plan-material-schema :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} valid-content? (m/validator schema/Content))

(defn ^{:stratum 0} allowed? [admission]
  (= :allow (get-in admission [:recovery/plan-decision-envelope :envelope/decision])))

(defn- ^{:stratum 0} criteria [checks refusals]
  (into {} (map (juxt :gate :passed?)) (concat (:results checks) refusals)))

(defn ^{:stratum 0} plan-matches? [admission content]
  (and (= (:recovery/plan admission) (:recovery/plan content))
       (= (:recovery/hash admission) (:recovery/hash content))
       (= (:recovery/hash content) (hash/content-hash (:recovery/plan content)))))

(defn- ^{:stratum 0} same-policy? [admission content]
  (let [old (get-in content [:recovery/plan-decision-envelope :envelope/pins])
        current (get-in admission [:recovery/plan-decision-envelope :envelope/pins])]
    (= (select-keys old schema/policy-fields) (select-keys current schema/policy-fields))))

(defn ^{:stratum 0} reference [id content]
  {:recovery/artifact-ref id :recovery/plan (:recovery/plan content) :recovery/hash (:recovery/hash content)})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} report [admission]
  (assoc (select-keys admission schema/admission-fields)
         :verification/kind schema/kind :verification/passed? (allowed? admission)
         :verification/criteria (criteria (:recovery/plan-gate-checks admission) (:recovery/plan-refusals admission))))

(defn- ^{:stratum 1} approved-report? [content]
  (let [checks (:recovery/plan-gate-checks content)
        results (:results checks)
        refusals (:recovery/plan-refusals content)
        pins (get-in content [:recovery/plan-decision-envelope :envelope/pins])]
    (and (true? (:verification/passed? content)) (allowed? content)
         (true? (:passed? checks)) (empty? (:failed-gates checks)) (empty? refusals)
         (every? :passed? results)
         (= (mapv :gate results) (:pins/rule-ids pins))
         (= (:verification/criteria content) (criteria checks refusals)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} valid? [ctx admission id value]
  (let [content (:artifact/content value)]
    (and (= id (:artifact/id value)) (= schema/artifact-type (:artifact/type value))
         (= schema/kind (get-in value [:artifact/metadata :opsv/material-kind]))
         (some? (get-in value [:artifact/metadata :evidence/content-protection]))
         (protected/valid? ctx value) (valid-content? content)
         (allowed? admission) (approved-report? content)
         (plan-matches? admission content) (same-policy? admission content))))

(comment ::fresh-current-admission-and-retained-producer-proof-both-required)
