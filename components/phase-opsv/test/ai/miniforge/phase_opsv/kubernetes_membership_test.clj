;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-membership-test
  (:require [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-membership-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} rolling-replacement-requires-both-real-controller-links-test
  (let [observed (fixtures/observation)]
    (is (identical? observed (phase/admit-kubernetes-deployment-membership observed)))
    (doseq [path [[:runtime/target :workload-uid]
                  [:kubernetes/namespace "metadata" "uid"]
                  [:kubernetes/deployment "metadata" "uid"]
                  [:kubernetes/replica-sets 0 "metadata" "ownerReferences" 0 "uid"]
                  [:kubernetes/pods "items" 0 "metadata" "ownerReferences" 0 "uid"]]]
      (is (= :conflict
              (:anomaly/type (phase/admit-kubernetes-deployment-membership
                               (assoc-in observed path "recreated-uid"))))))))

(deftest ^{:stratum 0} controller-kind-name-api-and-namespace-are-part-of-the-link-test
  (let [observed (fixtures/observation)]
    (doseq [[path value]
            [[[:kubernetes/cluster-id] "foreign-cluster"]
             [[:runtime/target :namespace] "foreign"]
             [[:kubernetes/deployment "metadata" "namespace"] "foreign"]
             [[:kubernetes/replica-sets 0 "metadata" "namespace"] "foreign"]
             [[:kubernetes/pods "items" 0 "metadata" "namespace"] "foreign"]
             [[:kubernetes/pods "items" 0 "metadata" "ownerReferences" 0 "name"] "same-uid-wrong-name"]
             [[:kubernetes/pods "items" 0 "metadata" "ownerReferences" 0 "kind"] "Deployment"]
             [[:kubernetes/pods "items" 0 "metadata" "ownerReferences" 0 "apiVersion"] "apps/v2"]
             [[:kubernetes/replica-sets 0 "metadata" "ownerReferences" 0 "name"] "other-deployment"]
             [[:kubernetes/replica-sets 0 "metadata" "ownerReferences" 0 "kind"] "StatefulSet"]
             [[:kubernetes/replica-sets 0 "metadata" "ownerReferences" 0 "apiVersion"] "v1"]]]
      (is (= :conflict (:anomaly/type
                         (phase/admit-kubernetes-deployment-membership (assoc-in observed path value))))))))

(deftest ^{:stratum 0} ambiguous-and-non-controller-owners-never-establish-membership-test
  (let [observed (fixtures/observation)]
    (doseq [path [[:kubernetes/pods "items" 0 "metadata" "ownerReferences"]
                  [:kubernetes/replica-sets 0 "metadata" "ownerReferences"]]]
      (let [reference (first (get-in observed path))]
        (doseq [references [[reference reference]
                            [(dissoc reference "controller")]
                            [(assoc reference "controller" false)]]]
          (is (= :conflict (:anomaly/type
                             (phase/admit-kubernetes-deployment-membership
                               (assoc-in observed path references))))))))))

(deftest ^{:stratum 0} duplicate-instance-and-logical-identities-fail-closed-test
  (let [observed (fixtures/observation)
        pod (get-in observed [:kubernetes/pods "items" 0])
        replica-set (first (:kubernetes/replica-sets observed))]
    (doseq [duplicate [pod
                      (assoc-in pod ["metadata" "name"] "other-name-same-uid")
                      (assoc-in pod ["metadata" "uid"] "other-uid-same-name")]]
      (is (= :conflict (:anomaly/type (phase/admit-kubernetes-deployment-membership
                                       (update-in observed [:kubernetes/pods "items"] conj duplicate))))))
    (is (= :conflict (:anomaly/type (phase/admit-kubernetes-deployment-membership
                                     (update observed :kubernetes/replica-sets conj replica-set)))))))

(deftest ^{:stratum 0} malformed-and-unsupported-observations-are-invalid-not-attested-test
  (let [observed (fixtures/observation)]
    (doseq [invalid [nil {} []
                    (assoc observed :membership/approved? true)
                    (assoc-in observed [:kubernetes/deployment "kind"] "StatefulSet")
                    (assoc-in observed [:kubernetes/pods "kind"] "ReplicaSetList")
                    (assoc-in observed [:kubernetes/pods "items" 0 "kind"] "Node")
                    (assoc-in observed [:kubernetes/pods "items" 0 "metadata" "ownerReferences"] [])
                    (assoc-in observed [:kubernetes/replica-sets 0 "metadata" "ownerReferences"] [{}])
                    (assoc-in observed [:kubernetes/pods "items" 0 "metadata" "ownerReferences" 0 "controller"] "true")]]
      (is (= :invalid-input (:anomaly/type (phase/admit-kubernetes-deployment-membership invalid)))))))

(deftest ^{:stratum 0} zero-population-and-native-list-typemeta-remain-observation-only-test
  (let [observed (fixtures/observation)
        empty-population (-> observed
                             (assoc :kubernetes/replica-sets [])
                             (assoc-in [:kubernetes/pods "items"] []))
        native-items (update-in observed [:kubernetes/pods "items"]
                               #(mapv (fn [pod] (dissoc pod "apiVersion" "kind")) %))
        pending-pods (update-in observed [:kubernetes/pods "items"]
                                #(mapv (fn [pod]
                                         (-> pod
                                             (update "spec" dissoc "nodeName")
                                             (assoc "status" {"phase" "Pending"}))) %))
        non-controller (assoc (first (get-in observed [:kubernetes/pods "items" 0 "metadata" "ownerReferences"]))
                             "controller" false "uid" "other-non-controller-owner")
        multiple-owners (update-in observed [:kubernetes/pods "items" 0 "metadata" "ownerReferences"]
                                    conj non-controller)]
    (doseq [value [empty-population native-items pending-pods multiple-owners]]
      (is (identical? value (phase/admit-kubernetes-deployment-membership value))))))

(comment ::membership-is-not-freshness-configuration-or-effect-authority)
