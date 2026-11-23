;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.schema.opsv-artifact-types-test
  (:require [ai.miniforge.schema.artifact-fixtures :as fixtures]
            [ai.miniforge.schema.interface :as schema]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private required-opsv-types
  "Exact N6 section 3.1.1 artifact tags required for N7."
  [:experiment-pack :operational-policy-proposal :opsv-verification-report :opsv-metric-snapshot])

(def ^{:stratum 0} ^:private existing-generic-types
  "Previously admitted generic artifact tags remain supported."
  [:spec :plan :adr :code :test :review :manifest :image :telemetry :incident])

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} required-opsv-artifact-tags-are-admitted-test
  (doseq [type required-opsv-types]
    (let [artifact (fixtures/artifact type)]
      (is (some #{type} schema/artifact-types))
      (is (schema/valid-artifact? artifact))
      (is (identical? artifact (schema/validate-anomaly schema/Artifact artifact))))))

(deftest ^{:stratum 1} existing-tags-remain-valid-and-unregistered-tags-remain-invalid-test
  (doseq [type existing-generic-types]
    (is (schema/valid-artifact? (fixtures/artifact type))))
  (doseq [type [:unregistered-opsv-report nil "opsv-verification-report"]]
    (is (false? (schema/valid-artifact? (fixtures/artifact type))))))

(comment ::type-support-does-not-prove-content-provenance-or-authority)
