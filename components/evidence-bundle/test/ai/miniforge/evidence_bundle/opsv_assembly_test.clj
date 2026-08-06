;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.evidence-bundle.opsv-assembly-test
  (:require
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.evidence-bundle.opsv-test-fixtures :as f]
   [ai.miniforge.response.interface :as response]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} error-codes
  [result]
  (set (map :code (:opsv.validation/errors result))))

(defn ^{:stratum 0} accumulated-store
  [evidence-value]
  (let [store (evidence/create-opsv-assembly-store)
        assembly (evidence/allocate-opsv-assembly! store f/workflow-id)
        bundle-id (:evidence-bundle/id assembly)]
    (evidence/accumulate-opsv-evidence!
     store bundle-id evidence-value)
    [store bundle-id]))

(deftest ^{:stratum 0} scalar-accumulation-is-a-single-reference
  (let [store (evidence/create-opsv-assembly-store)
        bundle-id (:evidence-bundle/id
                   (evidence/allocate-opsv-assembly! store f/workflow-id))
        event-id (first f/event-ids)
        result (evidence/accumulate-opsv-evidence!
                store bundle-id {:opsv/event-refs event-id})]
    (is (= #{event-id} (:opsv/event-refs result)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} finalize-preserves-preallocated-identity-once
  (let [[store bundle-id] (accumulated-store f/opsv-evidence)
        result (evidence/finalize-opsv-evidence!
                store bundle-id f/base-bundle f/opsv-evidence
                (set f/artifact-ids))]
    (is (= bundle-id (:evidence-bundle/id result)))
    (is (= f/opsv-evidence (:evidence/opsv result)))
    (is (string? (:evidence/content-hash result)))
    (is (m/validate evidence/OpsvEvidence (:evidence/opsv result)))
    (is (= :finalized
           (:opsv.assembly/status (evidence/get-opsv-assembly store bundle-id))))
    (testing "final bundles cannot be finalized or accumulated again"
      (is (= :anomalies/conflict
             (:anomaly/category
              (evidence/finalize-opsv-evidence!
               store bundle-id :invalid-base-bundle f/opsv-evidence
               (set f/artifact-ids)))))
      (is (= :anomalies/conflict
             (:anomaly/category
              (evidence/accumulate-opsv-evidence! store bundle-id {})))))))

(deftest ^{:stratum 1} finalize-rejects-invalid-base-bundle
  (let [[store bundle-id] (accumulated-store f/opsv-evidence)
        result (evidence/finalize-opsv-evidence!
                store bundle-id :invalid-base-bundle f/opsv-evidence
                (set f/artifact-ids))]
    (is (response/anomaly-map? result))
    (is (contains? (error-codes result) :invalid-base-bundle))))

(deftest ^{:stratum 1} finalize-rejects-missing-artifact
  (let [[store bundle-id] (accumulated-store f/opsv-evidence)
        result (evidence/finalize-opsv-evidence!
                store bundle-id f/base-bundle f/opsv-evidence
                (disj (set f/artifact-ids) f/diff-artifact-id))]
    (is (response/anomaly-map? result))
    (is (contains? (error-codes result) :referenced-artifact-not-found))
    (is (= [f/diff-artifact-id]
           (->> (:opsv.validation/errors result)
                (filter #(= :referenced-artifact-not-found (:code %)))
                first
                :missing)))))

(deftest ^{:stratum 1} finalize-rejects-uncorrelated-governed-effect
  (let [uncorrelated (assoc f/opsv-evidence :opsv/capability-refs [])
        [store bundle-id] (accumulated-store
                           uncorrelated)
        result (evidence/finalize-opsv-evidence!
                store bundle-id f/base-bundle uncorrelated
                (set f/artifact-ids))]
    (is (response/anomaly-map? result))
    (is (contains? (error-codes result) :uncorrelated-governed-effect))))

(deftest ^{:stratum 1} finalize-rejects-reference-loss
  (let [[store bundle-id] (accumulated-store f/opsv-evidence)
        incomplete (update f/opsv-evidence :opsv/event-refs pop)
        result (evidence/finalize-opsv-evidence!
                store bundle-id f/base-bundle incomplete
                (set f/artifact-ids))]
    (is (response/anomaly-map? result))
    (is (contains? (error-codes result) :event-reference-mismatch))))

(deftest ^{:stratum 1} finalize-reports-each-reference-mismatch
  (doseq [[expected-code change]
          [[:artifact-reference-mismatch
            #(update % :opsv/artifact-refs pop)]
           [:capability-reference-mismatch
            #(assoc % :opsv/capability-refs [])]
           [:governed-effect-mismatch
            #(assoc-in % [:opsv/actuation :governed-effects] [])]]]
    (let [[store bundle-id] (accumulated-store f/opsv-evidence)
          result (evidence/finalize-opsv-evidence!
                  store bundle-id f/base-bundle (change f/opsv-evidence)
                  (set f/artifact-ids))]
      (is (response/anomaly-map? result))
      (is (contains? (error-codes result) expected-code)))))

(deftest ^{:stratum 1} finalize-rejects-unindexed-detailed-artifact
  (let [evidence-value (update f/opsv-evidence :opsv/artifact-refs pop)
        [store bundle-id] (accumulated-store evidence-value)
        result (evidence/finalize-opsv-evidence!
                store bundle-id f/base-bundle evidence-value
                (set f/artifact-ids))]
    (is (response/anomaly-map? result))
    (is (contains? (error-codes result)
                   :detailed-artifact-reference-missing))))

(deftest ^{:stratum 1} finalize-rejects-duplicate-aggregate-references
  (doseq [reference-key [:opsv/event-refs
                         :opsv/artifact-refs
                         :opsv/capability-refs]]
    (let [duplicate-evidence
          (update f/opsv-evidence reference-key #(conj % (first %)))
          [store bundle-id]
          (accumulated-store duplicate-evidence)
          result (evidence/finalize-opsv-evidence!
                  store bundle-id f/base-bundle duplicate-evidence
                  (set f/artifact-ids))]
      (is (response/anomaly-map? result))
      (is (contains? (error-codes result) :invalid-opsv-evidence))))
  (let [duplicate-evidence
        (update-in f/opsv-evidence [:opsv/actuation :governed-effects]
                   #(conj % (first %)))
        [store bundle-id] (accumulated-store duplicate-evidence)
        result (evidence/finalize-opsv-evidence!
                store bundle-id f/base-bundle duplicate-evidence
                (set f/artifact-ids))]
    (is (response/anomaly-map? result))
    (is (contains? (error-codes result) :invalid-opsv-evidence))))

(deftest ^{:stratum 1} malformed-reference-type-returns-anomaly
  (let [store (evidence/create-opsv-assembly-store)
        bundle-id (:evidence-bundle/id
                   (evidence/allocate-opsv-assembly! store f/workflow-id))
        malformed (assoc f/opsv-evidence :opsv/event-refs f/workflow-id)
        result (evidence/finalize-opsv-evidence!
                store bundle-id f/base-bundle malformed
                (set f/artifact-ids))]
    (is (response/anomaly-map? result))
    (is (contains? (error-codes result) :invalid-opsv-evidence))))

(deftest ^{:stratum 1} scalar-artifact-availability-returns-anomaly
  (let [[store bundle-id] (accumulated-store f/opsv-evidence)
        result (evidence/finalize-opsv-evidence!
                store bundle-id f/base-bundle f/opsv-evidence
                f/pack-artifact-id)]
    (is (response/anomaly-map? result))
    (is (contains? (error-codes result) :referenced-artifact-not-found))))

(deftest ^{:stratum 1} finalization-canonicalizes-reference-order
  (let [second-capability-id "grant-2"
        second-effect (assoc f/governed-effect
                             :evidence/capability-id second-capability-id)
        evidence-value (-> f/opsv-evidence
                           (update :opsv/capability-refs
                                   conj second-capability-id)
                           (update-in [:opsv/actuation :governed-effects]
                                      conj second-effect))
        reordered (-> evidence-value
                      (update :opsv/event-refs #(vec (reverse %)))
                      (update :opsv/artifact-refs #(vec (reverse %)))
                      (update :opsv/capability-refs #(vec (reverse %)))
                      (update-in [:opsv/actuation :governed-effects]
                                 #(vec (reverse %))))
        finalize (fn [input]
                   (let [[store bundle-id] (accumulated-store input)]
                     (evidence/finalize-opsv-evidence!
                      store bundle-id f/base-bundle input
                      (set f/artifact-ids))))]
    (with-redefs [random-uuid (constantly f/canonical-bundle-id)]
      (is (= (finalize evidence-value) (finalize reordered))))))
