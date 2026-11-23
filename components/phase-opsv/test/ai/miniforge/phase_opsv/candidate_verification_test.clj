;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.candidate-verification-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.candidate-verification-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} operations [calls] (mapv first @calls))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-valid [ctx directory baseline]
  (let [calls (atom [])
        candidate (merge baseline (environment/changes))
        result (fixtures/verify ctx (partial fixtures/prepare calls (fixtures/observations baseline))
                                (partial fixtures/measure calls directory (fixtures/observations candidate)))
        [_ request retained] (second @calls)
        pin (get-in retained [:artifact/content :comparison/pin])
        final-id (get-in result [:opsv/phase-artifact-ids :verification-environment])
        final (artifact/read-published directory final-id)
        assembly (evidence/get-opsv-assembly (:opsv/evidence-assembly-store ctx)
                   (get-in ctx [:execution/input :opsv/evidence-bundle-id]))]
    (is (= [:prepare :verify] (operations calls)))
    (is (true? (get-in result [:opsv/verification-result :passed?])))
    (is (= :opsv-verification-report (:artifact/type retained) (:artifact/type final)))
    (is (= baseline (get-in pin [:comparison/contract :comparison/baseline])))
    (is (= (:comparison/pin request) pin))
    (is (= (:verification/id request) (get-in pin [:comparison/contract :comparison/binding :verification/id])))
    (is (= (:execution/id ctx) (get-in pin [:comparison/contract :comparison/binding :workflow/id])))
    (is (= (fixtures/observations baseline)
           (select-keys (get-in retained [:artifact/content :comparison/preparation])
                        (keys (fixtures/observations baseline)))))
    (is (= (:artifact/id retained) (get-in final [:artifact/content :comparison/preparation-artifact-ref])))
    (is (every? (:opsv/artifact-refs assembly) [(:artifact/id retained) final-id]))
    (is (every? (set (:opsv/artifact-refs result)) [(:artifact/id retained) final-id]))
    (is (= {:provider/snapshot candidate}
           (get-in final [:artifact/content :comparison/observation :environment/raw-observations])))))

(defn- ^{:stratum 1} verify-pre-effect-failures [ctx directory baseline]
  (let [calls (atom [])
        prepare (partial fixtures/prepare calls (fixtures/observations baseline))
        measure (partial fixtures/measure calls directory (fixtures/observations baseline))]
    (doseq [configured [(assoc-in ctx [:execution/opts :opsv/verification-environment] nil)
                        (update ctx :execution/opts dissoc :opsv/artifact-directory)
                        (assoc-in ctx [:execution/opts :opsv/verification-environment :candidate/hash] (apply str (repeat 64 "0")))]]
      (is (anomaly/anomaly? (fixtures/verify configured prepare measure))))
    (is (empty? @calls))
    (with-redefs [artifact/publish! (fn [& _] (anomaly/anomaly :unavailable "injected publication failure" {}))]
      (is (anomaly/anomaly? (fixtures/verify ctx prepare measure))))
    (is (= [:prepare] (operations calls)))
    (reset! calls [])
    (with-redefs [evidence/accumulate-opsv-evidence! (fn [& _] (anomaly/anomaly :unavailable "no acknowledgement" {}))]
      (is (anomaly/anomaly? (fixtures/verify ctx prepare measure))))
    (is (= [:prepare] (operations calls)))))

(defn- ^{:stratum 1} verify-preparation-drift [ctx directory baseline]
  (doseq [observed [(assoc-in (fixtures/observations baseline) [:runtime/target :namespace-uid] "replaced")
                   (assoc-in (fixtures/observations baseline) [:environment-fingerprint :config-hash] "unexpected")
                   (dissoc (fixtures/observations baseline) :environment/raw-observations)]]
    (let [calls (atom [])
          result (fixtures/verify ctx (partial fixtures/prepare calls observed)
                                      (partial fixtures/measure calls directory (fixtures/observations baseline)))]
      (is (anomaly/anomaly? result))
      (is (= [:prepare] (operations calls))))))

(defn- ^{:stratum 1} verify-candidate-drift [ctx directory baseline]
  (let [candidate (merge baseline (environment/changes))]
    (doseq [observed [(fixtures/observations baseline)
                     (assoc-in (fixtures/observations candidate) [:environment-fingerprint :node-pools] ["other"])
                     (assoc-in (fixtures/observations candidate) [:runtime/target :workload-uid] "replaced")]]
      (let [calls (atom [])
            result (fixtures/verify ctx (partial fixtures/prepare calls (fixtures/observations baseline))
                                        (partial fixtures/measure calls directory observed))
            output (get-in result [:anomaly/data :opsv/phase-output])
            report (artifact/read-published directory (get-in output [:opsv/phase-artifact-ids :verification-environment]))]
        (is (= :conflict (:anomaly/type result)))
        (is (map? (get-in result [:anomaly/data :opsv/environment-drift])))
        (is (= [:prepare :verify] (operations calls)))
        (is (= :conflict (get-in report [:artifact/content :comparison/outcome :anomaly/type])))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} retention-is-confirmed-before-effects-and-linked-after-observation-test
  (fixtures/with-context verify-valid))

(deftest ^{:stratum 2} invalid-host-input-and-unconfirmed-evidence-cannot-reach-candidate-test
  (fixtures/with-context verify-pre-effect-failures))

(deftest ^{:stratum 2} fresh-preparation-must-match-target-baseline-and-retain-raw-data-test
  (fixtures/with-context verify-preparation-drift))

(deftest ^{:stratum 2} undeclared-drift-cannot-become-a-successful-candidate-test
  (fixtures/with-context verify-candidate-drift))
