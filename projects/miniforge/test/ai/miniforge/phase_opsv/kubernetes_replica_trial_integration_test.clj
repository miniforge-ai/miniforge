;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-trial-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as replicas]
            [ai.miniforge.phase-opsv.kubernetes-replica-trial-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-verification-fixtures :as native]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-refused [transform reads? fixture]
  (let [changed (transform fixture)
        [result calls] (fixtures/prepare changed (:trial/routes changed))]
    (is (anomaly/anomaly? result))
    (is (= reads? (boolean (seq calls))))
    (is (every? #(= :get (:method %)) calls))))

(defn- ^{:stratum 0} check-exact [fixture]
  (let [[result calls] (fixtures/prepare fixture (:trial/routes fixture))
        scope (:opsv/experiment result)
        payload (:kubernetes/request result)
        expected (fixtures/expected-patch fixture)]
    (is (not (anomaly/anomaly? result)))
    (is (= :effect/deploy (:effect/class result)))
    (is (= (get-in fixture [:trial/request :effect/id]) (:effect/id result)))
    (is (= :trial-apply (:opsv/operation scope)))
    (is (= (get-in fixture [:input :load :limits :opsv/duration-ms]) (:opsv/duration-ms scope)))
    (is (= expected (:body payload)))
    (is (= (hash/content-hash payload) (:opsv/payload-hash scope)))
    (is (seq calls))
    (is (every? #(= :get (:method %)) calls))
    (is (not-any? #(contains? result %) [:effect/grant-id :effect/authority :recovery/verified? :slo/passed?]))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} malformed-lineage-current-admission-foreign-provider-and-caller-current-refuse-before-native-reads-test
  (doseq [transform [#(assoc-in % [:trial/request :recovery/artifact-ref] (random-uuid))
                     #(assoc-in % [:trial/request :recovery/input :load :comparison/artifact-ref] (random-uuid))
                     #(assoc-in % [:trial/request :trial/provider :kubernetes/context] "foreign-context")
                     #(assoc-in % [:trial/request :effect/id] (get-in % [:input :load :limits :effect/id]))
                     #(assoc-in % [:ctx :execution/opts :opsv/recovery-plan-admission :recovery/approved-plan-hashes] #{})
                     #(assoc-in % [:trial/request :current] {})
                     #(assoc-in % [:trial/request :replicas/count] nil)
                     #(assoc-in % [:trial/request :approved?] true)]]
    (fixtures/with-input (partial check-refused transform false))))

(deftest ^{:stratum 1} bounded-trial-parameters-still-require-the-current-original-and-predeclared-candidate-range-test
  (fixtures/with-input (partial check-refused #(assoc-in % [:trial/request :replicas/count] fixtures/out-of-bounds-count) true)))

(deftest ^{:stratum 1} candidate-state-unrelated-drift-and-foreign-physical-targets-are-not-a-new-trial-baseline-test
  (doseq [[path value] [[[inventory/deployment-path "spec" "replicas"] replicas/trial-count]
                       [[inventory/deployment-path "spec" "template" "metadata" "labels" "unrelated"] "drift"]
                       [[native/cluster-path "metadata" "uid"] "foreign-cluster"]
                       [[inventory/namespace-path "metadata" "uid"] "foreign-namespace"]]]
    (fixtures/with-input (partial check-refused #(assoc-in % (into [:trial/routes] path) value) true))))

(deftest ^{:stratum 1} trial-uses-actual-fresh-uid-revision-and-only-the-bounded-replica-field-test
  (doseq [count [replicas/trial-count (inc replicas/trial-count)]]
    (fixtures/with-input #(check-exact (assoc-in % [:trial/request :replicas/count] count)))))

(deftest ^{:stratum 1} replacement-replicas-do-not-require-adopting-a-new-original-test
  (fixtures/with-input #(check-exact (update % :trial/routes fixtures/replacement-routes))))

(comment
  (fixtures/with-input check-exact))
