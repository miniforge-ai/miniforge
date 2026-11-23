;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-absence-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.store :as transactions]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-load-absence-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-verification-fixtures :as native]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} namespace-path "/api/v1/namespaces/staging")

(defn- ^{:stratum 0} check-foreign-pod [transform {:keys [native pod] :as fixture}]
  (let [routes (assoc (:routes native) (fixtures/pod-path fixture) (transform pod))
        [result calls] (fixtures/observe fixture routes)]
    (is (= :unresolved (:load/resource-state result)))
    (is (= 5 (count calls)))))

(defn- ^{:stratum 0} check-roots [after? path transform {:keys [native] :as fixture}]
  (let [original (:routes native)
        changed (transform original path)
        respond! (fn [calls options]
                   (wire/reply! (if (or (not after?) (>= (count @calls) 3)) changed original) calls options))
        [result _calls] (fixtures/observe-with fixture respond!)]
    (is (= :unresolved (:load/resource-state result)))))

(defn- ^{:stratum 0} check-admission [fixture]
  (doseq [changed [(assoc-in fixture [:ctx :execution/opts :opsv/recovery-plan-admission
                                     :recovery/approved-plan-hashes] #{})
                   (assoc-in fixture [:request :ownership/runtime :provider :kubernetes/context] "foreign-context")
                   (assoc-in fixture [:request :approved?] true)
                   (assoc-in fixture [:request :recovery/artifact-ref] (random-uuid))]]
    (let [[result calls] (fixtures/observe changed (get-in fixture [:native :routes]))]
      (is (anomaly/anomaly? result))
      (is (empty? calls)))))

(defn- ^{:stratum 0} check-current-record [{:keys [record runtime native] :as fixture}]
  (let [changed (assoc record :effect/state :unknown-outcome)
        stored (transactions/transition! (:effects-directory runtime) record changed)
        [result calls] (fixtures/observe fixture (:routes native))]
    (is (= changed stored))
    (is (anomaly/anomaly? result))
    (is (empty? calls))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-state [present? {:keys [native pod record] :as fixture}]
  (let [routes (cond-> (:routes native) present? (assoc (fixtures/pod-path fixture) pod))
        [result calls] (fixtures/observe fixture routes)]
    (is (= (if present? :present :absent) (:load/resource-state result)))
    (is (= (get-in record [:effect/observed :kubernetes/resource]) (:kubernetes/resource result)))
    (is (= #{:load/resource-state :kubernetes/resource} (set (keys result))))
    (is (= [native/cluster-path namespace-path (fixtures/pod-path fixture)
            native/cluster-path namespace-path] (mapv wire/path calls)))
    (is (every? #(= :get (:method %)) calls))))

(deftest ^{:stratum 1} foreign-malformed-or-null-pods-are-unresolved-not-absent-test
  (doseq [transform [#(assoc-in % ["metadata" "uid"] "foreign-worker")
                     #(assoc-in % ["metadata" "namespace"] "other-namespace")
                     #(update % "metadata" dissoc "resourceVersion")
                     #(assoc % "kind" "Job") (constantly nil)]]
    (fixtures/with-created (partial check-foreign-pod transform))))

(deftest ^{:stratum 1} replaced-cluster-or-namespace-before-or-after-404-cannot-prove-absence-test
  (doseq [after? [false true]
          path [native/cluster-path namespace-path]
          transform [(fn [routes path] (assoc-in routes [path "metadata" "uid"] "replaced-root"))
                     (fn [routes path] (assoc-in routes [path "metadata" "name"] "foreign-root"))
                     (fn [routes path] (assoc-in routes [path "kind"] "Pod"))
                     (fn [routes path] (assoc routes path nil)) dissoc]]
    (fixtures/with-created (partial check-roots after? path transform))))

(deftest ^{:stratum 1} current-retained-admission-and-closed-input-precede-all-provider-reads-test
  (fixtures/with-created check-admission))

(deftest ^{:stratum 1} durable-granted-native-creation-is-reread-before-all-provider-reads-test
  (fixtures/with-created check-current-record))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} absence-is-observed-between-original-physical-root-checks-without-recovery-claims-test
  (fixtures/with-created (partial check-state false)))

(deftest ^{:stratum 2} same-owned-resource-still-present-does-not-establish-cleanup-test
  (fixtures/with-created (partial check-state true)))

(comment
  (fixtures/with-created (partial check-state false)))
