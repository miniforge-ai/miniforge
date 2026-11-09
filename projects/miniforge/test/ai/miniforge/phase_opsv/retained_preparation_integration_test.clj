;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.retained-preparation-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.retained-preparation-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} read-retained [{:keys [ctx request id]}]
  (phase/read-retained-verification-preparation! ctx request id))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-original-and-final [{:keys [ctx request value output] :as input}]
  (is (= (get-in value [:artifact/content :comparison/preparation]) (read-retained input)))
  (let [final-id (get-in output [:opsv/phase-artifact-ids :verification-environment])]
    (is (not= (:id input) final-id))
    (is (= :conflict (:anomaly/type (phase/read-retained-verification-preparation! ctx request final-id))))))

(defn- ^{:stratum 1} check-malformed-input [input]
  (let [realized (atom 0)
        deferred (delay (swap! realized inc) {})]
    (doseq [[path value] [[[:id] nil] [[:id] "not-a-uuid"] [[:ctx :execution/id] nil]
                          [[:ctx :opsv/evidence-assembly-store] deferred]
                          [[:ctx :execution/opts :opsv/artifact-directory] " "]
                          [[:request :comparison/pin] {}]]]
      (is (= :invalid-input (:anomaly/type (read-retained (assoc-in input path value))))))
    (is (zero? @realized))))

(defn- ^{:stratum 1} check-request-correlation [input]
  (doseq [[path value] [[[:ctx :execution/id] (random-uuid)]
                        [[:request :verification/id] (random-uuid)]
                        [[:request :candidate/hash] (hash/content-hash :foreign-candidate)]
                        [[:request :experiment-pack/hash] (hash/content-hash :foreign-pack)]
                        [[:request :runtime/target :namespace] "foreign"]
                        [[:request :comparison/pin :comparison/hash] (hash/content-hash :foreign-pin)]]]
    (let [result (read-retained (assoc-in input path value))]
      (is (= :conflict (:anomaly/type result)))
      (is (= {} (:anomaly/data result))))))

(defn- ^{:stratum 1} check-current-acknowledgement [{:keys [ctx id] :as input}]
  (let [store (:opsv/evidence-assembly-store ctx)
        bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        original @store
        assembly (get original bundle-id)]
    (doseq [changed [(update assembly :opsv/artifact-refs disj id)
                     (assoc assembly :evidence-bundle/id (random-uuid))
                     (assoc assembly :evidence-bundle/workflow-id (random-uuid))
                     (assoc assembly :opsv.assembly/status :unknown)]]
      (reset! store (assoc original bundle-id changed))
      (is (= :conflict (:anomaly/type (read-retained input)))))
    (reset! store (assoc original bundle-id (assoc assembly :opsv.assembly/status :finalized)))
    (is (not (anomaly/anomaly? (read-retained input))))
    (reset! store original)))

(defn- ^{:stratum 1} check-retained-content [{:keys [ctx request value] :as input}]
  (let [content (:artifact/content value)
        pin (:comparison/pin content)
        changed-pin (assoc pin :comparison/hash (hash/content-hash :wrong-digest))]
    (doseq [[content request] [[(assoc-in content [:comparison/preparation :verification/id] (random-uuid)) request]
                               [(assoc-in content [:comparison/preparation :environment-fingerprint :cluster] "foreign") request]
                               [(assoc content :comparison/pin changed-pin) (assoc request :comparison/pin changed-pin)]]]
      (let [id (fixtures/publish! ctx (fixtures/record ctx content))]
        (is (= :conflict (:anomaly/type (read-retained (assoc input :id id :request request)))))))
    (let [tampered (-> value (assoc :artifact/id (random-uuid))
                       (assoc-in [:artifact/content :comparison/preparation :environment-fingerprint :cluster] "foreign"))
          foreign (fixtures/record (assoc ctx :execution/id (random-uuid)) content)]
      (doseq [record [tampered foreign (fixtures/record ctx content :verification-environment :review)
                       (fixtures/record ctx content :verification :opsv-verification-report)]]
        (let [id (fixtures/publish! ctx record)]
          (is (= :conflict (:anomaly/type (read-retained (assoc input :id id))))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} original-preparation-is-readable-but-final-report-is-not-an-original-test
  (fixtures/with-retained check-original-and-final))

(deftest ^{:stratum 2} malformed-input-is-rejected-without-dereferencing-untrusted-state-test
  (fixtures/with-retained check-malformed-input))

(deftest ^{:stratum 2} every-request-producer-and-comparison-binding-is-required-test
  (fixtures/with-retained check-request-correlation))

(deftest ^{:stratum 2} current-assembly-acknowledgement-is-required-on-every-read-test
  (fixtures/with-retained check-current-acknowledgement))

(deftest ^{:stratum 2} acknowledged-content-still-requires-original-baseline-digest-and-integrity-test
  (fixtures/with-retained check-retained-content))
