;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governed-replica-proposal-refusals-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.governed-replica-proposal-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.retained-preparation-test-support :as storage]
            [ai.miniforge.phase-opsv.retained-preparation-fixtures :as retained]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-retention-required [{:keys [ctx input directory id]}]
  (let [store (:opsv/evidence-assembly-store ctx)
        bundle (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        original @store]
    (swap! store update-in [bundle :opsv/artifact-refs] disj id)
    (is (= :conflict (:anomaly/type (phase/prepare-governed-kubernetes-replica-proposal! ctx input))))
    (reset! store original)
    (storage/withdraw! directory id)
    (is (= :unavailable (:anomaly/type (phase/prepare-governed-kubernetes-replica-proposal! ctx input))))))

(defn- ^{:stratum 0} check-correlation-and-inputs [{:keys [ctx input]}]
  (doseq [[path value] [[[:declaration :workflow/id] (random-uuid)]
                        [[:request :verification/id] (random-uuid)]
                        [[:request :candidate/hash] (hash/content-hash :foreign)]
                        [[:request :experiment-pack/hash] (hash/content-hash :foreign)]
                        [[:request :runtime/target :namespace] "foreign"]
                        [[:comparison/artifact-ref] (random-uuid)]
                        [[:limits :effect/id] nil] [[:limits :kubernetes/context] ""]
                        [[:limits :opsv/duration-ms] 0] [[:limits :opsv/load-limits] {}]
                        [[:limits :environment/class] :production]
                        [[:baseline] (:baseline input)]
                        [[:parameters :opsv/operation] :start-load]]]
    (is (anomaly/anomaly? (phase/prepare-governed-kubernetes-replica-proposal! ctx (assoc-in input path value))))))

(defn- ^{:stratum 0} check-protected-original [{:keys [ctx input value]}]
  (let [content (:artifact/content value)
        prefix [:comparison/preparation :environment/raw-observations :kubernetes/verification :kubernetes/source]
        deployment (conj prefix :kubernetes/inventory :kubernetes/deployment)]
    (doseq [[path replacement] [[(conj deployment "spec" "replicas") nil]
                                [(conj deployment "metadata" "uid") "replacement"]
                                [(conj deployment "metadata" "name") "foreign"]
                                [(conj prefix :kubernetes/inventory :kubernetes/namespace "metadata" "uid") "foreign"]]]
      (let [changed (assoc-in content path replacement)
            id (retained/publish! ctx (retained/record ctx changed))
            result (phase/prepare-governed-kubernetes-replica-proposal! ctx (assoc input :comparison/artifact-ref id))]
        (is (= :conflict (:anomaly/type result)))))))

(defn- ^{:stratum 0} check-fresh-capture-remains-complete [{:keys [ctx input value]}]
  (let [reported (get-in value [:artifact/content :comparison/preparation :environment/raw-observations
                               :kubernetes/verification :kubernetes/source])
        result (phase/prepare-governed-kubernetes-replica-proposal! ctx (assoc input :current reported))]
    (is (= :invalid-input (:anomaly/type result)))
    (is (not (anomaly/anomaly? (phase/prepare-governed-kubernetes-replica-proposal! ctx input))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} acknowledged-and-currently-readable-original-material-is-required-test
  (fixtures/with-input check-retention-required))

(deftest ^{:stratum 1} foreign-or-malformed-inputs-cannot-produce-proposals-test
  (fixtures/with-input check-correlation-and-inputs))

(deftest ^{:stratum 1} original-recovery-fields-must-be-usable-and-exactly-target-bound-test
  (fixtures/with-input check-protected-original))

(deftest ^{:stratum 1} reporting-evidence-is-not-a-substitute-for-a-complete-fresh-capture-test
  (fixtures/with-input check-fresh-capture-remains-complete))
