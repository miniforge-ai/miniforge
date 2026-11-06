;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-request-integration-test
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-preparation-fixtures :as native]
            [ai.miniforge.phase-opsv.kubernetes-replica-recovery-fixtures :as recovery]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} exact-native-trial-payload-has-identity-and-revision-tests-test
  (let [input (fixtures/input)
        prepared (fixtures/prepare input)
        payload (:kubernetes/request prepared)
        revision (get-in input [:current :kubernetes/inventory :kubernetes/deployment "metadata" "resourceVersion"])
        operations (mapv #(mapv % ["op" "path" "value"]) (:body payload))]
    (is (= :effect/deploy (:effect/class prepared)))
    (is (= :trial-apply (:opsv/operation prepared)))
    (is (= (get-in input [:declaration :runtime/target]) (:runtime/target prepared)))
    (is (= :patch (:method payload)))
    (is (= "/apis/apps/v1/namespaces/staging/deployments/catalog" (:path payload)))
    (is (= "application/json-patch+json" (:media-type payload)))
    (is (= [["test" "/metadata/uid" "deployment-uid"]
            ["test" "/metadata/resourceVersion" revision]
            ["replace" "/spec/replicas" fixtures/trial-count]] operations))
    (is (= (hash/content-hash payload) (:opsv/payload-hash prepared)))
    (is (= ["apps/v1" "Deployment" "staging" "catalog" "deployment-uid"]
           (mapv (first (:opsv/resources prepared)) [:api-version :kind :namespace :name :uid])))))

(deftest ^{:stratum 0} restoration-uses-original-count-and-fresh-server-preconditions-test
  (let [input (recovery/input)
        prepared (fixtures/prepare input)
        payload (:kubernetes/request prepared)
        operations (:body payload)]
    (is (= fixtures/trial-count (get-in input [:current :kubernetes/inventory :kubernetes/deployment "spec" "replicas"])))
    (is (= :restore-baseline (:opsv/operation prepared)))
    (is (= fixtures/original-count (get (last operations) "value")))
    (is (= fixtures/fresh-revision (get (second operations) "value")))
    (is (= "deployment-uid" (get (first operations) "value")))
    (is (= (hash/content-hash payload) (:opsv/payload-hash prepared)))))

(deftest ^{:stratum 0} closed-operations-counts-and-native-input-shapes-are-required-test
  (let [input (fixtures/input)]
    (doseq [[path value] [[[:parameters :opsv/operation] :arbitrary-patch]
                         [[:parameters :replicas/count] -1]
                         [[:parameters :replicas/count] (inc (long Integer/MAX_VALUE))]
                         [[:parameters :payload] []]
                         [[:current :kubernetes/inventory :kubernetes/deployment "metadata" "resourceVersion"] nil]
                         [[:baseline :environment/raw-observations] {}]
                         [[:baseline :environment/raw-observations :kubernetes/verification :kubernetes/source
                           :kubernetes/inventory :kubernetes/deployment "spec" "replicas"] nil]]]
      (is (= :invalid-input (:anomaly/type (fixtures/prepare (assoc-in input path value))))))
    (is (= :invalid-input (:anomaly/type (fixtures/prepare
                                         (assoc input :parameters (assoc (fixtures/parameters :restore-baseline)
                                                                        :replicas/count fixtures/trial-count))))))))

(deftest ^{:stratum 0} reused-names-and-mismatched-native-targets-are-refused-test
  (let [input (fixtures/input)]
    (doseq [[path value] [[[:kubernetes/inventory :runtime/target :cluster] "foreign"]
                         [[:kubernetes/inventory :kubernetes/cluster-id] "foreign"]
                         [[:kubernetes/inventory :kubernetes/namespace "metadata" "uid"] "replacement"]
                         [[:kubernetes/inventory :kubernetes/deployment "metadata" "uid"] "replacement"]
                         [[:kubernetes/inventory :kubernetes/deployment "metadata" "namespace"] "foreign"]
                         [[:kubernetes/inventory :kubernetes/deployment "metadata" "name"] "foreign"]]]
      (is (= :conflict (:anomaly/type (fixtures/prepare (assoc-in input (into [:current] path) value))))))))

(deftest ^{:stratum 0} original-baseline-and-exact-preparation-binding-are-required-test
  (let [input (fixtures/input)]
    (doseq [request (native/mismatched-requests (:request input))]
      (is (= :conflict (:anomaly/type (fixtures/prepare (assoc input :request request))))))
    (doseq [[path value] [[[:baseline :verification/id] (random-uuid)]
                         [[:baseline :runtime/target :workload-uid] "foreign"]
                         [[:baseline :environment-fingerprint :config-hash] "changed"]
                         [[:baseline :environment/raw-observations :kubernetes/verification :kubernetes/source
                           :kubernetes/inventory :kubernetes/deployment "metadata" "uid"] "replacement"]]]
      (is (= :conflict (:anomaly/type (fixtures/prepare (assoc-in input path value))))))))

(deftest ^{:stratum 0} candidate-population-and-pack-blast-radius-both-constrain-trials-test
  (let [input (fixtures/input)]
    (doseq [count [0 fixtures/original-count (inc (inc fixtures/trial-count))]]
      (is (= :conflict (:anomaly/type (fixtures/prepare (assoc-in input [:parameters :replicas/count] count))))))
    (let [contract (get-in input [:request :comparison/pin :comparison/contract])
          widened (assoc-in contract [:comparison/replica-contract :candidate :replicas/max-count]
                             (inc (inc fixtures/trial-count)))
          pin (phase/pin-verification-environment widened)
          request (assoc (:request input) :comparison/pin pin)
          changed (assoc input :request request :parameters (assoc (:parameters input) :replicas/count
                                                                   (inc (inc fixtures/trial-count))))]
      (is (= :conflict (:anomaly/type (fixtures/prepare changed)))))))

(deftest ^{:stratum 0} fresh-revisions-produce-new-payload-hashes-test
  (let [input (fixtures/input)
        original (fixtures/prepare input)
        changed (fixtures/prepare (assoc-in input [:current :kubernetes/inventory :kubernetes/deployment
                                                   "metadata" "resourceVersion"] fixtures/fresh-revision))]
    (is (string? (:opsv/payload-hash changed)))
    (is (not= (:opsv/payload-hash original) (:opsv/payload-hash changed)))))

(deftest ^{:stratum 0} changed-current-counts-cannot-exceed-the-declared-trial-or-recovery-plan-test
  (let [trial (fixtures/input)
        recovery (recovery/input)
        outside (inc (inc fixtures/trial-count))]
    (doseq [[input current desired] [[trial 0 fixtures/trial-count]
                                    [trial 1 (inc fixtures/trial-count)]
                                    [recovery outside nil]]]
      (let [observed (assoc-in input [:current :kubernetes/inventory :kubernetes/deployment "spec" "replicas"] current)
            changed (cond-> observed (some? desired) (assoc-in [:parameters :replicas/count] desired))]
        (is (= :conflict (:anomaly/type (fixtures/prepare changed))))))))

(comment ::request-data-is-not-a-current-grant-provider-effect-or-recovery-verdict)
