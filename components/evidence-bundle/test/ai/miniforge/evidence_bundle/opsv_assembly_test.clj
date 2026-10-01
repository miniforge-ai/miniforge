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
   [ai.miniforge.evidence-bundle.opsv-finalization-candidate :as candidate]
   [ai.miniforge.evidence-bundle.opsv-finalization-publication :as publication]
   [ai.miniforge.evidence-bundle.opsv-test-fixtures :as f]
   [ai.miniforge.evidence-bundle.scanner :as scanner]
   [ai.miniforge.response.interface :as response]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} prepare-with-concurrent-event [prepare store event-id record base opsv available]
  (let [prepared (prepare record base opsv available)]
    (evidence/accumulate-opsv-evidence!
      store (:evidence-bundle/id record) {:opsv/event-refs event-id})
    prepared))

(defn- ^{:stratum 0} seal-at-fixed-time [seal bundle _]
  (seal bundle #inst "2026-09-30T00:00:00Z"))

(defn- ^{:stratum 0} failed-scan [_]
  (throw (IllegalStateException. "injected scanner failure")))

(defn- ^{:stratum 0} false-declaration [bundle field]
  (let [unsigned (dissoc bundle :evidence/content-hash :evidence/signature)
        invalid (assoc unsigned field false)]
    (assoc invalid :evidence/content-hash (evidence/content-hash invalid))))

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

(deftest ^{:stratum 1} finalization-does-not-realize-deferred-availability
  (let [[store id] (accumulated-store f/opsv-evidence)
        realized? (atom false)
        available (lazy-seq (reset! realized? true) (seq f/artifact-ids))
        before @store
        result (evidence/finalize-opsv-evidence! store id f/base-bundle f/opsv-evidence available)]
    (is (response/anomaly-map? result))
    (is (false? @realized?))
    (is (= before @store))))

(deftest ^{:stratum 1} finalization-requires-published-fields-before-cas
  (doseq [base [(dissoc f/base-bundle :evidence/event-links)
                (update f/base-bundle :evidence/outcome dissoc :outcome/tier)]]
    (let [[store id] (accumulated-store f/opsv-evidence)
          before @store
          result (evidence/finalize-opsv-evidence! store id base f/opsv-evidence (set f/artifact-ids))]
      (is (response/anomaly-map? result))
      (is (= before @store)))))

(deftest ^{:stratum 1} redaction-cannot-publish-an-invalid-canonical-value
  (let [opsv (assoc-in f/opsv-evidence [:opsv/policy-proposals 0 :scaling :AKIAIOSFODNN7EXAMPLE] "test")
        [store id] (accumulated-store opsv)
        before @store
        result (evidence/finalize-opsv-evidence! store id f/base-bundle opsv (set f/artifact-ids))]
    (is (response/anomaly-map? result))
    (is (= before @store))))

(deftest ^{:stratum 1} sealing-scans-redacts-and-records-compliance-before-hashing
  (let [[store id] (accumulated-store f/opsv-evidence)
        base (assoc-in f/base-bundle [:evidence/intent :intent/description] "AKIAABCDEFGHIJKLMNOP")
        bundle (evidence/finalize-opsv-evidence! store id base f/opsv-evidence (set f/artifact-ids))]
    (is (= "[REDACTED]" (get-in bundle [:evidence/intent :intent/description])))
    (is (true? (:compliance/sensitive-data bundle)))
    (is (= :redacted (:compliance/pii-handling bundle)))
    (is (:valid? (evidence/validate-canonical-bundle bundle))))
  (let [[store id] (accumulated-store f/opsv-evidence)
        before @store
        result (with-redefs [scanner/scan-artifact failed-scan]
                 (evidence/finalize-opsv-evidence! store id f/base-bundle f/opsv-evidence (set f/artifact-ids)))]
    (is (response/anomaly-map? result))
    (is (= before @store))))

(deftest ^{:stratum 1} payment-cards-cannot-survive-the-published-seal
  (doseq [card ["4111 1111 1111 1111" 4111111111111111 4111111111111111N]]
    (let [[store id] (accumulated-store f/opsv-evidence)
          base (assoc f/base-bundle :test/payment-card card)
          bundle (evidence/finalize-opsv-evidence! store id base f/opsv-evidence (set f/artifact-ids))]
      (is (= "[REDACTED]" (:test/payment-card bundle)))
      (is (true? (:evidence/contains-pii? bundle)))
      (is (= :redacted (:compliance/pii-handling bundle)))
      (is (:valid? (evidence/validate-canonical-bundle bundle))))))

(deftest ^{:stratum 1} metadata-only-redaction-is-recorded-in-the-seal
  (let [[store id] (accumulated-store f/opsv-evidence)
        base (assoc f/base-bundle :test/value (with-meta [] {:card 500000000009}))
        bundle (evidence/finalize-opsv-evidence! store id base f/opsv-evidence (set f/artifact-ids))]
    (is (= {:card "[REDACTED]"} (meta (:test/value bundle))))
    (is (true? (:evidence/contains-pii? bundle)))
    (is (= :redacted (:compliance/pii-handling bundle)))
    (is (:valid? (evidence/validate-canonical-bundle bundle)))))

(deftest ^{:stratum 1} concurrent-accumulation-invalidates-the-prepared-candidate
  (let [[store bundle-id] (accumulated-store f/opsv-evidence)
        event-id (random-uuid)
        prepare (partial prepare-with-concurrent-event candidate/prepare store event-id)
        result (with-redefs [candidate/prepare prepare]
                 (evidence/finalize-opsv-evidence!
                   store bundle-id f/base-bundle f/opsv-evidence (set f/artifact-ids)))
        assembly (evidence/get-opsv-assembly store bundle-id)]
    (is (response/anomaly-map? result))
    (is (contains? (error-codes result) :event-reference-mismatch))
    (is (= :assembling (:opsv.assembly/status assembly)))
    (is (contains? (:opsv/event-refs assembly) event-id))
    (is (nil? (:opsv.assembly/bundle assembly)))))

(deftest ^{:stratum 1} finalize-preserves-preallocated-identity-once
  (let [[store bundle-id] (accumulated-store f/opsv-evidence)
        result (evidence/finalize-opsv-evidence!
                store bundle-id f/base-bundle f/opsv-evidence
                (set f/artifact-ids))]
    (is (= bundle-id (:evidence-bundle/id result)))
    (is (= f/opsv-evidence (:evidence/opsv result)))
    (is (string? (:evidence/content-hash result)))
    (is (inst? (:evidence/sealed-at result)))
    (is (= (:evidence/sealed-at result) (:compliance/created-at result)))
    (is (:valid? (evidence/validate-canonical-bundle result)))
    (is (evidence/valid-finalized-opsv-bundle?
          (evidence/get-opsv-assembly store bundle-id) result (set f/artifact-ids)))
    (is (not (evidence/valid-finalized-opsv-bundle?
               (evidence/get-opsv-assembly store bundle-id) result #{})))
    (is (not (evidence/valid-finalized-opsv-bundle?
               (assoc (evidence/get-opsv-assembly store bundle-id) :opsv/event-refs #{})
               result (set f/artifact-ids))))
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

(deftest ^{:stratum 1} restore-adopts-the-existing-seal-without-resealing
  (let [[store id] (accumulated-store f/opsv-evidence)
        snapshot @store
        bundle (evidence/finalize-opsv-evidence! store id f/base-bundle f/opsv-evidence (set f/artifact-ids))
        restored (atom snapshot)
        tampered (assoc-in bundle [:evidence/outcome :outcome/success] false)]
    (is (response/anomaly-map? (evidence/restore-finalized-opsv-bundle! restored tampered (set f/artifact-ids))))
    (is (= snapshot @restored))
    (swap! restored assoc-in [id :opsv.assembly/bundle] bundle)
    (is (= bundle (evidence/restore-finalized-opsv-bundle! restored bundle (set f/artifact-ids))))
    (is (= :finalized (:opsv.assembly/status (evidence/get-opsv-assembly restored id))))
    (is (= bundle (evidence/restore-finalized-opsv-bundle! restored bundle (set f/artifact-ids))))
    (is (response/anomaly-map? (evidence/restore-finalized-opsv-bundle! restored bundle #{})))
    (is (response/anomaly-map? (evidence/restore-finalized-opsv-bundle! (atom {}) nil #{})))))

(deftest ^{:stratum 1} restoration-rejects-sensitive-metadata-without-changing-retained-state
  (let [[store id] (accumulated-store f/opsv-evidence)
        snapshot @store
        bundle (evidence/finalize-opsv-evidence! store id f/base-bundle f/opsv-evidence (set f/artifact-ids))]
    (doseq [metadata [{:secret "private-value"} {:card 500000000009}]
            candidate [(with-meta bundle metadata)
                       (update bundle :evidence/outcome with-meta metadata)]]
      (let [restored (atom snapshot)]
        (is (:valid? (evidence/validate-canonical-bundle candidate)))
        (is (response/anomaly-map?
              (evidence/restore-finalized-opsv-bundle! restored candidate (set f/artifact-ids))))
        (is (identical? snapshot @restored))))))

(deftest ^{:stratum 1} restoration-rejects-false-sensitivity-declarations
  (let [[store id] (accumulated-store f/opsv-evidence)
        snapshot @store
        base (assoc-in f/base-bundle [:evidence/intent :intent/description] "000-00-0000")
        bundle (evidence/finalize-opsv-evidence! store id base f/opsv-evidence (set f/artifact-ids))]
    (is (true? (:compliance/sensitive-data bundle)))
    (is (true? (:evidence/contains-pii? bundle)))
    (doseq [field [:compliance/sensitive-data :evidence/contains-pii?]]
      (let [candidate (false-declaration bundle field)
            restored (atom snapshot)]
        (is (:valid? (evidence/validate-canonical-bundle candidate)))
        (is (response/anomaly-map?
              (evidence/restore-finalized-opsv-bundle! restored candidate (set f/artifact-ids))))
        (is (identical? snapshot @restored))))))

(deftest ^{:stratum 1} finalize-rejects-invalid-base-bundle
  (let [[store bundle-id] (accumulated-store f/opsv-evidence)
        result (evidence/finalize-opsv-evidence!
                store bundle-id :invalid-base-bundle f/opsv-evidence
                (set f/artifact-ids))]
    (is (response/anomaly-map? result))
    (is (contains? (error-codes result) :invalid-base-bundle))))

(deftest ^{:stratum 1} finalize-rejects-noncanonical-base-without-sealing
  (doseq [base [(assoc-in f/base-bundle [:evidence/outcome :outcome/success] "true")
                (assoc-in f/base-bundle [:evidence/intent :intent/constraints] [{}])
                (dissoc f/base-bundle :evidence/policy-checks)]]
    (let [[store bundle-id] (accumulated-store f/opsv-evidence)
          result (evidence/finalize-opsv-evidence!
                  store bundle-id base f/opsv-evidence (set f/artifact-ids))]
      (is (response/anomaly-map? result))
      (is (contains? (error-codes result) :invalid-evidence-bundle))
      (is (= :assembling (:opsv.assembly/status (evidence/get-opsv-assembly store bundle-id)))))))

(deftest ^{:stratum 1} finalize-refuses-to-repair-or-reseal-existing-evidence
  (doseq [key [:evidence/content-hash :evidence/signature :evidence/sealed-at]]
    (let [[store bundle-id] (accumulated-store f/opsv-evidence)
          before @store
          base (assoc f/base-bundle key nil)
          result (evidence/finalize-opsv-evidence!
                  store bundle-id base f/opsv-evidence (set f/artifact-ids))]
      (is (response/anomaly-map? result))
      (is (contains? (error-codes result) :sealed-base-bundle))
      (is (= before @store)))))

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
  (let [uncorrelated (assoc f/opsv-evidence :opsv/grant-refs [])
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
           [:grant-reference-mismatch
            #(assoc % :opsv/grant-refs [])]
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
                         :opsv/grant-refs]]
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
  (let [second-grant-id #uuid "00000000-0000-0000-0000-000000000113"
        second-effect (assoc f/governed-effect
                             :evidence/grant-id second-grant-id)
        evidence-value (-> f/opsv-evidence
                           (update :opsv/grant-refs conj second-grant-id)
                           (update-in [:opsv/actuation :governed-effects]
                                      conj second-effect))
        reordered (-> evidence-value
                      (update :opsv/event-refs #(vec (reverse %)))
                      (update :opsv/artifact-refs #(vec (reverse %)))
                      (update :opsv/grant-refs #(vec (reverse %)))
                      (update-in [:opsv/actuation :governed-effects]
                                 #(vec (reverse %))))
        finalize (fn [input]
                   (let [[store bundle-id] (accumulated-store input)]
                     (evidence/finalize-opsv-evidence!
                      store bundle-id f/base-bundle input
                      (set f/artifact-ids))))]
    (with-redefs [random-uuid (constantly f/canonical-bundle-id)
                  publication/sealed-bundle (partial seal-at-fixed-time publication/sealed-bundle)]
      (is (= (finalize evidence-value) (finalize reordered))))))
