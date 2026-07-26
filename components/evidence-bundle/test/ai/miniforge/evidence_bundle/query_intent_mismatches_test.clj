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
(ns ai.miniforge.evidence-bundle.query-intent-mismatches-test
  "Tests for query-intent-mismatches-impl's :time-range filtering,
   including bundles missing :evidence-bundle/created-at (schema
   requires the key, but nothing enforces it against malformed/corrupted
   stored data)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.protocols.impl.provenance-tracer :as prov]))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures
(defn- ^{:stratum 0} mismatch-bundle
  "Bundle shaped to be picked up as an intent mismatch (declared != actual,
   not passed?). `created-at` may be nil to simulate a corrupted bundle
   missing the schema-required :evidence-bundle/created-at field."
  [created-at]
  (cond-> {:evidence-bundle/id (random-uuid)
           :evidence-bundle/workflow-id (random-uuid)
           :evidence/semantic-validation
           {:semantic-validation/declared-intent {:intent/type :import}
            :semantic-validation/actual-behavior {:intent/type :create}
            :semantic-validation/passed? false}}
    created-at (assoc :evidence-bundle/created-at created-at)))

;------------------------------------------------------------------------------ Layer 1

;; :time-range criteria — well-formed bundles
(deftest ^{:stratum 1} query-intent-mismatches-time-range-well-formed-test
  (testing "Bundle with :evidence-bundle/created-at inside the range is included"
    (let [now (java.time.Instant/now)
          bundle (mismatch-bundle now)
          bundles (atom {(:evidence-bundle/id bundle) bundle})
          results (prov/query-intent-mismatches-impl
                   bundles {:time-range [(.minusSeconds now 60) (.plusSeconds now 60)]})]
      (is (= 1 (count results)))))

  (testing "Bundle with :evidence-bundle/created-at outside the range is excluded"
    (let [now (java.time.Instant/now)
          bundle (mismatch-bundle now)
          bundles (atom {(:evidence-bundle/id bundle) bundle})
          results (prov/query-intent-mismatches-impl
                   bundles {:time-range [(.plusSeconds now 60) nil]})]
      (is (empty? results)))))

;; :time-range criteria — bundle missing :evidence-bundle/created-at
(deftest ^{:stratum 1} query-intent-mismatches-time-range-missing-created-at-test
  (testing "Bundle missing :evidence-bundle/created-at is excluded rather than throwing, when :start is set"
    (let [bundle (mismatch-bundle nil)
          bundles (atom {(:evidence-bundle/id bundle) bundle})]
      (is (empty? (prov/query-intent-mismatches-impl
                   bundles {:time-range [(java.time.Instant/now) nil]})))))

  (testing "Bundle missing :evidence-bundle/created-at is excluded rather than throwing, when :end is set"
    (let [bundle (mismatch-bundle nil)
          bundles (atom {(:evidence-bundle/id bundle) bundle})]
      (is (empty? (prov/query-intent-mismatches-impl
                   bundles {:time-range [nil (java.time.Instant/now)]}))))))

(comment
  (clojure.test/run-tests)
  :leave-this-here)
