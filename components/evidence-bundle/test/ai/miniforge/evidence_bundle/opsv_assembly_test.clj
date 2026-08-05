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
     store bundle-id
     (select-keys evidence-value
                  [:opsv/event-refs :opsv/artifact-refs
                   :opsv/capability-refs :opsv/governed-effects]))
    [store bundle-id]))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} finalize-preserves-preallocated-identity-once
  (let [[store bundle-id] (accumulated-store
                           (assoc f/opsv-evidence
                                  :opsv/governed-effects [f/governed-effect]))
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
               store bundle-id f/base-bundle f/opsv-evidence
               (set f/artifact-ids)))))
      (is (= :anomalies/conflict
             (:anomaly/category
              (evidence/accumulate-opsv-evidence! store bundle-id {})))))))

(deftest ^{:stratum 1} finalize-rejects-missing-artifact
  (let [[store bundle-id] (accumulated-store
                           (assoc f/opsv-evidence
                                  :opsv/governed-effects [f/governed-effect]))
        result (evidence/finalize-opsv-evidence!
                store bundle-id f/base-bundle f/opsv-evidence
                (disj (set f/artifact-ids) f/diff-artifact-id))]
    (is (response/anomaly-map? result))
    (is (contains? (error-codes result) :referenced-artifact-not-found))))

(deftest ^{:stratum 1} finalize-rejects-uncorrelated-governed-effect
  (let [uncorrelated (assoc f/opsv-evidence :opsv/capability-refs [])
        [store bundle-id] (accumulated-store
                           (assoc uncorrelated
                                  :opsv/governed-effects [f/governed-effect]))
        result (evidence/finalize-opsv-evidence!
                store bundle-id f/base-bundle uncorrelated
                (set f/artifact-ids))]
    (is (response/anomaly-map? result))
    (is (contains? (error-codes result) :uncorrelated-governed-effect))))

(deftest ^{:stratum 1} finalize-rejects-reference-loss
  (let [[store bundle-id] (accumulated-store
                           (assoc f/opsv-evidence
                                  :opsv/governed-effects [f/governed-effect]))
        incomplete (update f/opsv-evidence :opsv/event-refs pop)
        result (evidence/finalize-opsv-evidence!
                store bundle-id f/base-bundle incomplete
                (set f/artifact-ids))]
    (is (response/anomaly-map? result))
    (is (contains? (error-codes result) :event-reference-mismatch))))
