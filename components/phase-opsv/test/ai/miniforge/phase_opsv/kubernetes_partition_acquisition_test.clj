;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-partition-acquisition-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-partition-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} native-complete-inventory-produces-a-retained-proven-member-view-test
  (let [expected (fixtures/with-non-controller-rbac-owner)
        [raw calls] (fixtures/capture-lists! expected)
        result (phase/partition-kubernetes-deployment-inventory raw)]
    (is (= 6 (count calls)))
    (is (= expected raw))
    (is (identical? raw (:kubernetes/inventory result)))
    (is (= 2 (count (get-in result [:kubernetes/membership :kubernetes/pods "items"]))))
    (is (= 3 (count (get-in result [:kubernetes/inventory :kubernetes/pod-inventory "items"]))))))

(deftest ^{:stratum 0} native-acquisition-is-not-an-ownership-verdict-test
  (let [expected (assoc-in (fixtures/observation)
                           [:kubernetes/pod-inventory "items" 0 "metadata" "ownerReferences" 0 "uid"] "missing-parent")
        [raw calls] (fixtures/capture-lists! expected)]
    (is (not (anomaly/anomaly? raw)))
    (is (= 6 (count calls)))
    (is (= :conflict (:anomaly/type (phase/partition-kubernetes-deployment-inventory raw))))))

(comment ::only-injected-http-no-cluster-experiments-or-mutations)
