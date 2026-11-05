;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-environment-fingerprint-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.environment-fingerprint-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-environment-fingerprint-fixtures :as fixtures]
            [malli.core :as m]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} canonical-values-bind-original-semantic-projection-without-execution-verdict-test
  (let [{:keys [capture bounds]} (fixtures/input)
        result (phase/project-kubernetes-environment-fingerprint capture bounds)
        fingerprint (:environment-fingerprint result)]
    (is (not (anomaly/anomaly? result)))
    (is (m/validate schema/Fingerprint fingerprint))
    (is (identical? capture (:kubernetes/source result)))
    (is (= (get-in capture [:kubernetes/inventory :runtime/target]) (:runtime/target result)))
    (is (= "staging-cluster" (:cluster fingerprint)))
    (is (= :kubernetes/environment-configuration-v1
           (get-in result [:kubernetes/environment-projection :kubernetes/projection])))
    (is (= (:config-hash fingerprint) (artifact/content-digest (:kubernetes/environment-projection result))))
    (is (= 2 (count (:comparison/replica-observations result))))
    (is (= 2 (get-in result [:kubernetes/controller-counts :controller/deployment-replicas])))
    (is (= bounds (:comparison/bounds result)))
    (is (= 2 (count (:kubernetes/replica-configurations result))))
    (is (= 2 (count (:kubernetes/pod-lifecycles result))))
    (is (= :kubernetes/outside-workload-invariants-v1
           (get-in result [:kubernetes/outside-projection :kubernetes/projection])))
    (is (= :kubernetes/controller-desired-configuration-v1
           (get-in result [:kubernetes/controller-configuration :kubernetes/projection])))
    (is (not (contains? result :opsv/verification-result)))))

(deftest ^{:stratum 0} native-node-pool-inventory-not-current-placement-defines-canonical-pools-test
  (let [{:keys [capture bounds]} (fixtures/input)
        input (fixtures/with-pool capture "pool-b")
        observed (phase/project-kubernetes-environment-fingerprint input bounds)]
    (is (not (anomaly/anomaly? observed)))
    (is (= ["pool-a" "pool-b"] (get-in observed [:environment-fingerprint :node-pools])))))
