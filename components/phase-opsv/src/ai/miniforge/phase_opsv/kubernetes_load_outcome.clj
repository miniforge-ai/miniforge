;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-outcome
  "Admit a provider's creation receipt, never load execution or termination proof."
  (:require [ai.miniforge.phase-opsv.kubernetes-effect-binding :as native]
            [ai.miniforge.phase-opsv.kubernetes-load-provider-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-observation? (m/validator schema/Observation))

(defn- ^{:stratum 0} failure [outcome message]
  {:effect/outcome outcome :effect/failure (msg/ts message)})

(defn- ^{:stratum 0} requested? [expected actual]
  (cond
    (map? expected)
    (and (map? actual)
         (every? (fn [[key value]]
                   (and (contains? actual key) (requested? value (get actual key)))) expected))
    (vector? expected)
    (and (vector? actual) (= (count expected) (count actual))
         (every? true? (map requested? expected actual)))
    :else (= expected actual)))

(defn- ^{:stratum 0} inert? [pod]
  (let [spec (get pod "spec")
        extra-execution-fields #{"nodeName" "initContainers" "ephemeralContainers"}
        owners (get-in pod ["metadata" "ownerReferences"])]
    (and (not-any? #(contains? spec %) extra-execution-fields)
         (or (nil? owners) (= [] owners)))))

(defn- ^{:stratum 0} observed [record pod]
  {:kubernetes/resource (assoc (native/reference record) :uid (get-in pod ["metadata" "uid"]))
   :kubernetes/resource-version (get-in pod ["metadata" "resourceVersion"])})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} unchanged? [record pod]
  (let [expected (:body (native/request record))]
    (and (requested? expected pod)
         (= (get-in expected ["spec" "schedulingGates"]) (get-in pod ["spec" "schedulingGates"]))
         (inert? pod))))

(defn ^{:stratum 1} unconfirmed [] (failure :unknown-outcome :load/unconfirmed))

(defn ^{:stratum 1} refused [] (failure :failed :load/submission-refused))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} report [record pod]
  (if (and (valid-observation? pod) (unchanged? record pod))
    {:effect/outcome :succeeded :effect/observed (observed record pod)}
    (unconfirmed)))

(comment ::server-defaults-are-not-lifecycle-ownership-or-authorization-to-release-the-gate)
