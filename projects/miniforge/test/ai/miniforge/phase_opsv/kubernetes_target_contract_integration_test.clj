;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-target-contract-integration-test
  (:require [ai.miniforge.kubernetes-target.interface :as target]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} existing-native-producer-and-consumer-share-the-target-contract-test
  (let [declared (fixtures/declaration)
        identity (:runtime/target declared)]
    (is (m/validate target/Target identity))
    (is (m/validate phase/KubernetesDeploymentInventoryTarget declared))
    (doseq [field (keys identity)
            invalid [nil "" " \t\n" false]]
      (let [changed (assoc identity field invalid)]
        (is (not (m/validate target/Target changed)))
        (is (not (m/validate phase/KubernetesDeploymentInventoryTarget
                            (assoc declared :runtime/target changed))))))
    (is (not (m/validate phase/KubernetesDeploymentInventoryTarget
                        (assoc-in declared [:runtime/target :unknown] true))))))

(comment (clojure.test/run-tests 'ai.miniforge.phase-opsv.kubernetes-target-contract-integration-test))
