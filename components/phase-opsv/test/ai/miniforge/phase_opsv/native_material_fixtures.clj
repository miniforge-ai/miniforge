;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.native-material-fixtures
  (:require [ai.miniforge.phase-opsv.artifact-model :as artifacts]
            [ai.miniforge.phase-opsv.boundary.verification-protection :as protection]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as kube]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} literal-environment [value]
  [{"name" "SETTING" "value" value}])

(defn ^{:stratum 0} generic-secret-scalar [value]
  {:kubernetes.reporting/secret value})

(defn ^{:stratum 0} context []
  {:execution/id (random-uuid)
   :execution/input {:opsv/evidence-bundle-id (random-uuid)}})

(defn- ^{:stratum 0} string-comparator [left right]
  (if (and (string? left) (string? right))
    (compare left right) (throw (ClassCastException.))))

(defn- ^{:stratum 0} compare-native-values [left right]
  (compare (get-in left [:kubernetes/verification "spec" "containers" 0 "env" 0 "value"])
           (get-in right [:kubernetes/verification "spec" "containers" 0 "env" 0 "value"])))

(defn ^{:stratum 0} measurement-record [ctx value retained]
  (artifacts/record ctx
    {:opsv/verification-run value :opsv/verification-protection retained}
    [:verification-measurements :telemetry :opsv/verification-run]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} record [value]
  (artifacts/record (context) {:report value} [:verification :review :report]))

(defn ^{:stratum 1} frame [value]
  (let [pod (assoc-in (kube/pod) ["spec" "containers" 0 "env"] (literal-environment value))]
    {:kubernetes/verification pod}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} stamped-frame [value]
  (-> value frame reporting/prepare protection/stamp))

(defn ^{:stratum 2} comparator-sensitive-frame [value]
  (into (sorted-map-by string-comparator) (frame value)))

(defn ^{:stratum 2} sorted-carrier []
  (sorted-set-by compare-native-values (frame "opaque-native-value") (frame "123-45-6789")))
