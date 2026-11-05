;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-outside-projection-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.exception-policy :as exception-policy]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-container-test-support :as containers]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-outside-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-replica-fixtures :as replicas]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} project-outcome [input]
  (try+ (phase/project-kubernetes-outside-invariants input)
        (catch InterruptedException interrupted
          (.interrupt (Thread/currentThread))
          (throw interrupted))
        (catch exception-policy/nonfatal? failure failure)))

(defn- ^{:stratum 0} assert-drift [changes]
  (let [input (fixtures/input)
        baseline (:kubernetes/outside-hash (phase/project-kubernetes-outside-invariants input))]
    (doseq [[path value] changes
            :let [observed (phase/project-kubernetes-outside-invariants (assoc-in input path value))]]
      (is (not (anomaly/anomaly? observed)) (pr-str path))
      (is (not= baseline (:kubernetes/outside-hash observed)) (pr-str path)))))

(deftest ^{:stratum 0} raw-source-and-versioned-outside-projection-remain-separate-test
  (let [input (fixtures/input)
        result (phase/project-kubernetes-outside-invariants input)
        projection (:kubernetes/outside-projection result)]
    (is (not (anomaly/anomaly? result)))
    (is (identical? input (:kubernetes/source result)))
    (is (= :kubernetes/outside-workload-invariants-v1 (:kubernetes/projection projection)))
    (is (= (:kubernetes/outside-hash result) (artifact/content-digest projection)))
    (is (= 8 (count (:kubernetes/resources projection))))
    (is (not (contains? result :environment-fingerprint)))
    (is (not (contains? result :opsv/verification-result)))))

(deftest ^{:stratum 0} owned-replica-replacement-does-not-hide-outside-resource-drift-test
  (let [input (fixtures/input)
        baseline (phase/project-kubernetes-outside-invariants input)
        observed (phase/project-kubernetes-outside-invariants (replicas/replacement input))]
    (is (= (:kubernetes/outside-hash baseline) (:kubernetes/outside-hash observed)))
    (is (not= (:kubernetes/source baseline) (:kubernetes/source observed)))))

(deftest ^{:stratum 0} zero-owned-population-does-not-remove-unrelated-resources-test
  (let [input (fixtures/input)
        empty-owned (-> input
                        (update-in [:kubernetes/inventory :kubernetes/pod-inventory "items"] #(subvec % 2))
                        (update-in [:kubernetes/inventory :kubernetes/replica-set-inventory "items"] #(subvec % 2)))
        baseline (phase/project-kubernetes-outside-invariants input)
        observed (phase/project-kubernetes-outside-invariants empty-owned)]
    (is (= (:kubernetes/outside-hash baseline) (:kubernetes/outside-hash observed)))
    (is (= 8 (count (get-in observed [:kubernetes/outside-projection :kubernetes/resources]))))))

(deftest ^{:stratum 0} one-namespace-can-be-both-native-cluster-identity-and-target-without-hiding-duplicates-test
  (let [input (fixtures/cluster-namespace-input)
        result (phase/project-kubernetes-outside-invariants input)
        duplicate (update-in input [:kubernetes/nodes "items"] #(conj % (first %)))]
    (is (not (anomaly/anomaly? result)))
    (is (= 7 (count (get-in result [:kubernetes/outside-projection :kubernetes/resources]))))
    (is (anomaly/anomaly? (phase/project-kubernetes-outside-invariants duplicate)))))

(deftest ^{:stratum 0} collection-order-and-omitted-native-type-meta-do-not-fabricate-drift-test
  (let [input (fixtures/input)
        reordered (-> input
                      (update :kubernetes/configuration (comp vec reverse))
                      (update-in [:kubernetes/nodes "items"] (comp vec reverse))
                      (update-in [:kubernetes/inventory :kubernetes/pod-inventory "items"] (comp vec reverse))
                      (update-in [:kubernetes/inventory :kubernetes/replica-set-inventory "items"] (comp vec reverse)))
        native (reduce (fn [value path] (update-in value path (partial mapv #(dissoc % "apiVersion" "kind"))))
                       reordered [[:kubernetes/nodes "items"]
                                  [:kubernetes/inventory :kubernetes/pod-inventory "items"]
                                  [:kubernetes/inventory :kubernetes/replica-set-inventory "items"]])
        baseline (:kubernetes/outside-hash (phase/project-kubernetes-outside-invariants input))]
    (is (= baseline (:kubernetes/outside-hash (phase/project-kubernetes-outside-invariants reordered))))
    (is (= baseline (:kubernetes/outside-hash (phase/project-kubernetes-outside-invariants native))))))

(deftest ^{:stratum 0} secret-projection-uses-server-version-never-secret-values-or-annotations-test
  (let [input (fixtures/input)
        result (phase/project-kubernetes-outside-invariants input)
        profiles (get-in result [:kubernetes/outside-projection :kubernetes/resources])
        secret (first (filter #(= "Secret" (second (:resource/id %))) profiles))]
    (is (= {:secret/resource-version "42"} (:resource/configuration secret)))
    (is (not (.contains (pr-str (:kubernetes/outside-projection result)) "private-value")))
    (is (not (.contains (pr-str (:kubernetes/outside-projection result)) "cHJpdmF0ZS12YWx1ZQ==")))
    (is (identical? input (:kubernetes/source result)))))

(deftest ^{:stratum 0} incomplete-duplicate-foreign-and-uncorrelated-inputs-are-not-an-outside-proof-test
  (let [input (fixtures/input)]
    (doseq [changed [nil {}
                     (assoc-in input [:kubernetes/nodes "metadata" "continue"] "next")
                     (assoc-in input [:kubernetes/cluster "metadata" "name"] "not-cluster-identity")
                     (assoc-in input [:kubernetes/nodes "items" 0 "metadata" "uid"] "old-pod-uid")
                     (update-in input [:kubernetes/nodes "items"] #(conj % (first %)))
                     (update input :kubernetes/configuration #(conj % (first %)))
                     (assoc-in input [:kubernetes/inventory :runtime/target :namespace-uid] "foreign")
                     (assoc-in input [:kubernetes/inventory :kubernetes/pod-inventory "metadata" "continue"] "more")]]
      (is (anomaly/anomaly? (phase/project-kubernetes-outside-invariants changed))))))

(deftest ^{:stratum 0} owned-controller-configuration-is-deliberately-not-proven-by-outside-only-view-test
  (let [input (fixtures/input)
        changed (assoc-in input [:kubernetes/inventory :kubernetes/deployment "spec"] {"replicas" 999})
        baseline (phase/project-kubernetes-outside-invariants input)
        observed (phase/project-kubernetes-outside-invariants changed)]
    (is (= (:kubernetes/outside-hash baseline) (:kubernetes/outside-hash observed)))
    (is (= 999 (get-in observed [:kubernetes/source :kubernetes/inventory :kubernetes/deployment "spec" "replicas"])))))

(deftest ^{:stratum 0} nonportable-outside-configuration-cannot-escape-as-a-comparison-value-test
  (let [opaque (Object.)
        input (assoc-in (fixtures/input) [:kubernetes/inventory :kubernetes/pod-inventory "items" 2 "spec" "opaque"] opaque)
        result (phase/project-kubernetes-outside-invariants input)]
    (is (anomaly/anomaly? result))
    (is (not (.contains (pr-str result) (str opaque))))))

(deftest ^{:stratum 0} pending-and-unreported-outside-runtime-remain-explicit-raw-observations-test
  (let [input (fixtures/input)
        path [:kubernetes/inventory :kubernetes/pod-inventory "items" 2]]
    (doseq [status [{"phase" "Pending"} {"containerStatuses" []}]]
      (let [changed (assoc-in input (conj path "status") status)
            result (phase/project-kubernetes-outside-invariants changed)]
        (is (not (anomaly/anomaly? result)))
        (is (identical? changed (:kubernetes/source result)))))
    (is (not (anomaly/anomaly? (phase/project-kubernetes-outside-invariants (update-in input path dissoc "status")))))))

(deftest ^{:stratum 0} pending-runtime-image-identities-remain-unknown-not-fabricated-proofs-test
  (let [input (fixtures/input)
        path [:kubernetes/inventory :kubernetes/pod-inventory "items" 2 "status"]]
    (doseq [status [{"phase" "Pending" "containerStatuses" [(containers/status "service" "")]}
                    {"containerStatuses" [{"name" "service"}]}]]
      (let [changed (assoc-in input path status)
            result (phase/project-kubernetes-outside-invariants changed)]
        (is (not (anomaly/anomaly? result)))
        (is (identical? changed (:kubernetes/source result)))))))

(deftest ^{:stratum 0} partial-init-and-ephemeral-statuses-use-their-own-declarations-test
  (let [input (fixtures/input)
        pod [:kubernetes/inventory :kubernetes/pod-inventory "items" 2]]
    (doseq [[spec-field status-field] [["initContainers" "initContainerStatuses"]
                                       ["ephemeralContainers" "ephemeralContainerStatuses"]]]
      (let [changed (-> input
                        (assoc-in (into pod ["spec" spec-field]) [(containers/container "helper")])
                        (assoc-in (into pod ["status" status-field]) [(containers/status "helper" "")]))
            result (phase/project-kubernetes-outside-invariants changed)]
        (is (not (anomaly/anomaly? result)))
        (is (identical? changed (:kubernetes/source result)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} malformed-outside-runtime-status-is-an-anomaly-not-an-exception-or-success-test
  (let [input (fixtures/input)
        path [:kubernetes/inventory :kubernetes/pod-inventory "items" 2 "status"]]
    (doseq [status [{"containerStatuses" 42}
                    {"containerStatuses" [1]}
                    {"initContainerStatuses" {"name" "service"}}
                    {"ephemeralContainerStatuses" [(containers/status nil "")]}
                    {"containerStatuses" [(containers/status "service" 1)]}
                    {"containerStatuses" [{"name" "service"} {"name" "service"}]}
                    {"containerStatuses" [(containers/status "undeclared" "")]}]]
      (is (anomaly/anomaly? (project-outcome (assoc-in input path status)))))))

(deftest ^{:stratum 1} ownership-and-finalizer-permutations-preserve-hash-and-original-source-test
  (let [path [:kubernetes/inventory :kubernetes/pod-inventory "items" 2 "metadata"]
        input (-> (fixtures/input)
                  (assoc-in (conj path "finalizers") ["example.invalid/hold" "example.invalid/archive"])
                  (update-in (conj path "ownerReferences") conj (inventory/non-controller-rbac-reference)))
        baseline (phase/project-kubernetes-outside-invariants input)]
    (doseq [fields [["finalizers"] ["ownerReferences"] ["finalizers" "ownerReferences"]]
            :let [changed (reduce #(update-in %1 (conj path %2) (comp vec reverse)) input fields)
                  observed (project-outcome changed)]]
      (is (= (:kubernetes/outside-hash baseline) (:kubernetes/outside-hash observed)))
      (is (identical? changed (:kubernetes/source observed))))))

(deftest ^{:stratum 1} malformed-or-duplicate-metadata-collections-fail-before-canonicalization-test
  (let [path [:kubernetes/nodes "items" 0 "metadata"]
        reference (inventory/non-controller-rbac-reference)]
    (doseq [[field value] [["finalizers" nil] ["finalizers" 42] ["finalizers" ["hold" "hold"]]
                           ["finalizers" [1 "hold"]] ["ownerReferences" nil]
                           ["ownerReferences" [reference reference]]
                           ["ownerReferences" [(dissoc reference "uid")]]
                           ["ownerReferences" [1 reference]]]]
      (is (anomaly/anomaly? (project-outcome (assoc-in (fixtures/input) (conj path field) value)))))))

(deftest ^{:stratum 1} canonical-metadata-retains-finalizer-and-owner-reference-content-test
  (assert-drift [[[:kubernetes/nodes "items" 0 "metadata" "finalizers"] ["example.invalid/hold"]]
                 [[:kubernetes/configuration 0 "metadata" "ownerReferences"]
                  [(inventory/non-controller-rbac-reference)]]]))

(deftest ^{:stratum 1} cluster-and-namespace-invariants-remain-independent-test
  (assert-drift [[[:kubernetes/cluster "metadata" "uid"] "other-cluster"]
                 [[:kubernetes/inventory :kubernetes/namespace "metadata" "annotations"] {"scope" "changed"}]]))

(deftest ^{:stratum 1} node-name-reuse-runtime-and-desired-configuration-remain-visible-test
  (assert-drift [[[:kubernetes/nodes "items" 0 "metadata" "uid"] "same-node-name-new-uid"]
                 [[:kubernetes/nodes "items" 0 "status" "nodeInfo" "kernelVersion"] "other-kernel"]
                 [[:kubernetes/nodes "items" 0 "spec"] {"unschedulable" true}]]))

(deftest ^{:stratum 1} unrelated-pod-identity-template-and-runtime-image-remain-visible-test
  (let [outside [:kubernetes/inventory :kubernetes/pod-inventory "items" 2]]
    (assert-drift [[(into outside ["metadata" "uid"]) "other-outside-pod"]
                   [(into outside ["spec" "containers" 0 "image"]) "registry.invalid/other:version"]
                   [(into outside ["status" "containerStatuses" 0 "imageID"])
                    (str "sha256:" (apply str (repeat 64 "b")))]])))

(deftest ^{:stratum 1} unrelated-ownership-finalizers-and-deletion-state-remain-visible-test
  (let [outside [:kubernetes/inventory :kubernetes/pod-inventory "items" 2]]
    (assert-drift [[(into outside ["metadata" "finalizers"]) ["example.invalid/hold"]]
                   [(into outside ["metadata" "deletionTimestamp"]) "2026-11-04T00:00:00Z"]
                   [(into outside ["metadata" "ownerReferences"]) [(inventory/non-controller-rbac-reference)]]])))

(deftest ^{:stratum 1} unrelated-controller-and-configuration-resources-remain-visible-test
  (assert-drift [[[:kubernetes/inventory :kubernetes/replica-set-inventory "items" 2 "spec"] {"replicas" 2}]
                 [[:kubernetes/configuration 0 "data" "workers"] "5"]
                 [[:kubernetes/configuration 1 "metadata" "resourceVersion"] "43"]]))

(deftest ^{:stratum 1} pool-label-interpretation-is-bound-to-the-projection-test
  (assert-drift [[[:kubernetes/node-pool-label] "other-pool-interpretation"]]))
