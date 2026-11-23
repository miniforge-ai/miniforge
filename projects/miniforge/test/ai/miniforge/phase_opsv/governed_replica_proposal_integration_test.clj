;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governed-replica-proposal-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.governed-replica-proposal-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-replica-provider-fixtures :as provider]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as native]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-native-proposal [{:keys [ctx input native id]}]
  (let [proposal (phase/prepare-governed-kubernetes-replica-proposal! ctx input)
        binding (:opsv/experiment proposal)
        prepared (native/prepare native)
        limits (:limits input)]
    (is (m/validate grant/OpsvExperimentScope proposal))
    (is (= (:execution/id ctx) (:workflow-run/id proposal)))
    (is (= (:effect/id limits) (:effect/id proposal)))
    (is (= :effect/deploy (:effect/class proposal)))
    (is (= :staging (:environment/class binding)))
    (is (= id (:comparison/artifact-ref binding)))
    (is (= (get-in input [:request :comparison/pin :comparison/hash]) (:comparison/hash binding)))
    (is (= (select-keys (:request input) [:verification/id :candidate/hash :experiment-pack/hash])
           (select-keys binding [:verification/id :candidate/hash :experiment-pack/hash])))
    (is (= (dissoc limits :effect/id) (select-keys binding (keys (dissoc limits :effect/id)))))
    (is (= (:kubernetes/request prepared) (:kubernetes/request proposal)))
    (is (= (:opsv/payload-hash prepared) (:opsv/payload-hash binding)))
    (is (= (hash/content-hash (:kubernetes/request proposal)) (:opsv/payload-hash binding)))
    (is (= (:opsv/resources prepared) (:opsv/resources binding)))))

(defn- ^{:stratum 0} check-real-commit [{:keys [ctx input native]}]
  (let [proposal (phase/prepare-governed-kubernetes-replica-proposal! ctx input)
        execution (assoc native :proposal proposal :runtime (provider/settings))
        [committed calls] (provider/commit! execution (provider/response native) provider/dispatch!)]
    (is (= :succeeded (get-in committed [:result :effect/state])))
    (is (= (:result committed) (:stored committed)))
    (is (= proposal (get-in committed [:stored :effect/proposal])))
    (is (= 1 (count calls)))))

(defn- ^{:stratum 0} check-original-restoration [{:keys [ctx input]}]
  (let [changed (-> input
                    (assoc :parameters {:opsv/operation :restore-baseline})
                    (assoc-in [:limits :effect/id] (random-uuid))
                    (assoc-in [:current :kubernetes/inventory :kubernetes/deployment "spec" "replicas"] native/trial-count)
                    (assoc-in [:current :kubernetes/inventory :kubernetes/deployment "metadata" "resourceVersion"] native/fresh-revision))
        proposal (phase/prepare-governed-kubernetes-replica-proposal! ctx changed)
        operations (get-in proposal [:kubernetes/request :body])]
    (is (m/validate grant/OpsvExperimentScope proposal))
    (is (= :restore-baseline (get-in proposal [:opsv/experiment :opsv/operation])))
    (is (= native/original-count (get (last operations) "value")))
    (is (= native/fresh-revision (get (second operations) "value")))
    (is (anomaly/anomaly? (phase/prepare-governed-kubernetes-replica-proposal!
                           ctx (assoc-in changed [:parameters :replicas/count] 0))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} native-proposal-has-the-complete-exact-n10-scope-test
  (fixtures/with-input check-native-proposal))

(deftest ^{:stratum 1} proposal-producer-composes-with-real-issuance-durable-commit-and-provider-test
  (fixtures/with-input check-real-commit))

(deftest ^{:stratum 1} restoration-derives-original-count-and-fresh-preconditions-test
  (fixtures/with-input check-original-restoration))
