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
(ns ai.miniforge.evidence-bundle.query-bundles-test
  "Tests for query criteria matching against evidence bundles.

   Covers matches-criteria? and query-bundles-impl, including bundles
   missing :evidence-bundle/created-at (schema requires the key, but
   nothing enforces it against malformed/corrupted stored data)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.protocols.impl.evidence-bundle :as impl]))

;------------------------------------------------------------------------------ Layer 0

;; :time-range criteria — well-formed bundles
(deftest ^{:stratum 0} matches-criteria-time-range-well-formed-test
  (testing "Bundle with :evidence-bundle/created-at inside the range matches"
    (let [now (java.time.Instant/now)
          bundle {:evidence-bundle/created-at now}]
      (is (true? (impl/matches-criteria?
                  bundle
                  {:time-range [(.minusSeconds now 60) (.plusSeconds now 60)]})))))

  (testing "Bundle with :evidence-bundle/created-at outside the range does not match"
    (let [now (java.time.Instant/now)
          bundle {:evidence-bundle/created-at now}]
      (is (false? (impl/matches-criteria?
                   bundle
                   {:time-range [(.plusSeconds now 60) nil]}))))))

;; :time-range criteria — bundle missing :evidence-bundle/created-at
(deftest ^{:stratum 0} matches-criteria-time-range-missing-created-at-test
  (testing "Bundle missing :evidence-bundle/created-at filters out (does not match) rather than throwing, when :start is set"
    (let [bundle {}]
      (is (false? (impl/matches-criteria?
                   bundle
                   {:time-range [(java.time.Instant/now) nil]})))))

  (testing "Bundle missing :evidence-bundle/created-at filters out (does not match) rather than throwing, when :end is set"
    (let [bundle {}]
      (is (false? (impl/matches-criteria?
                   bundle
                   {:time-range [nil (java.time.Instant/now)]})))))

  (testing "query-bundles-impl excludes bundles missing :evidence-bundle/created-at instead of throwing an NPE"
    (let [now (java.time.Instant/now)
          good-bundle {:evidence-bundle/id (random-uuid)
                       :evidence-bundle/created-at now}
          bad-bundle {:evidence-bundle/id (random-uuid)}
          bundles (atom {(:evidence-bundle/id good-bundle) good-bundle
                         (:evidence-bundle/id bad-bundle) bad-bundle})
          results (impl/query-bundles-impl
                   bundles
                   {:time-range [(.minusSeconds now 60) (.plusSeconds now 60)]})]
      (is (= [good-bundle] results))
      (is (vector? results) "query-bundles-impl must return a vector per its documented contract"))))

(comment
  (clojure.test/run-tests)
  :leave-this-here)
