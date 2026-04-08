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

(ns ai.miniforge.policy-pack.mapping-test
  "Unit tests for mapping artifacts — schemas, loading, resolution, and projection.

   Covers:
   - Layer 0: Malli schema validation (MappingArtifact, MappingEntry, MappingAuthorship)
   - Layer 1: Loading from EDN files
   - Layer 2: resolve-mapping matches entries to pack rules/categories
   - Layer 2: project-report generates coverage summaries"
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.mapping :as sut]))

;; ============================================================================
;; Test fixtures
;; ============================================================================

(def now (java.time.Instant/now))

(def test-pack
  {:pack/id          "test-pack"
   :pack/name        "Test Pack"
   :pack/version     "1.0.0"
   :pack/description "Test"
   :pack/author      "test"
   :pack/categories  [{:category/id :operations :category/name "Ops" :category/rules [:test/ops-rule]}]
   :pack/rules       [{:rule/id          :test/copyright
                        :rule/title       "Copyright Header"
                        :rule/description "Require copyright header"
                        :rule/severity    :minor
                        :rule/category    "810"
                        :rule/applies-to  {}
                        :rule/detection   {:type :content-scan :pattern "copyright"}
                        :rule/enforcement {:action :warn :message "Missing copyright"}}
                       {:rule/id          :test/ops-rule
                        :rule/title       "Ops Rule"
                        :rule/description "Operations check"
                        :rule/severity    :major
                        :rule/category    "500"
                        :rule/applies-to  {}
                        :rule/detection   {:type :content-scan :pattern "test"}
                        :rule/enforcement {:action :warn :message "Warning"}}]
   :pack/created-at  now
   :pack/updated-at  now})

(def test-mapping
  {:mapping/id      :test-to-vanta/core
   :mapping/version "1.0.0"
   :mapping/source  {:mapping/system-kind :pack
                     :mapping/system-id   :test/core
                     :mapping/system-version "1.0.0"}
   :mapping/target  {:mapping/system-kind :framework
                     :mapping/system-id   :vanta/soc2}
   :mapping/entries [{:source/rule    :test/copyright
                      :target/control "CC6.2"
                      :mapping/type   :exact}
                     {:source/category :operations
                      :target/control  "CC7.1"
                      :mapping/type    :broad
                      :mapping/notes   "Broad category coverage"}
                     {:source/rule    :test/nonexistent
                      :target/control "CC8.1"
                      :mapping/type   :partial}
                     {:target/control "CC9.1"
                      :mapping/type   :none
                      :mapping/notes  "No mapping exists"}]
   :mapping/authorship {:publisher   :test
                        :confidence  :high
                        :validated-at "2026-04-01"}})

;; ============================================================================
;; Schema validation tests
;; ============================================================================

(deftest valid-mapping-test
  (testing "well-formed mapping passes validation"
    (is (true? (sut/valid-mapping? test-mapping))))

  (testing "mapping without entries fails"
    (is (false? (sut/valid-mapping? (dissoc test-mapping :mapping/entries)))))

  (testing "mapping without source fails"
    (is (false? (sut/valid-mapping? (dissoc test-mapping :mapping/source)))))

  (testing "mapping without id fails"
    (is (false? (sut/valid-mapping? (dissoc test-mapping :mapping/id)))))

  (testing "entry with invalid mapping type fails"
    (is (false? (sut/valid-mapping?
                 (assoc test-mapping :mapping/entries
                        [{:source/rule :test/foo
                          :mapping/type :invalid}]))))))

(deftest mapping-entry-types-test
  (testing "all four mapping types are valid"
    (doseq [mt [:exact :broad :partial :none]]
      (is (true? (sut/valid-mapping?
                  (assoc test-mapping :mapping/entries
                         [{:source/rule :test/copyright
                           :target/control "CC1.1"
                           :mapping/type mt}])))))))

(deftest mapping-authorship-test
  (testing "mapping without authorship is valid"
    (is (true? (sut/valid-mapping? (dissoc test-mapping :mapping/authorship)))))

  (testing "all confidence levels are valid"
    (doseq [c [:high :medium :low :unvalidated]]
      (is (true? (sut/valid-mapping?
                  (assoc-in test-mapping [:mapping/authorship :confidence] c)))))))

;; ============================================================================
;; Resolution tests
;; ============================================================================

(deftest resolve-mapping-test
  (testing "matched rule entry has :matched? true"
    (let [resolved (sut/resolve-mapping test-mapping test-pack)
          entry    (first (filter #(= :test/copyright (:source/rule %)) resolved))]
      (is (true? (:matched? entry)))
      (is (= "Copyright Header" (:rule-title entry)))))

  (testing "unmatched rule entry has :matched? false"
    (let [resolved (sut/resolve-mapping test-mapping test-pack)
          entry    (first (filter #(= :test/nonexistent (:source/rule %)) resolved))]
      (is (false? (:matched? entry)))))

  (testing "category-based entry matches pack categories"
    (let [resolved (sut/resolve-mapping test-mapping test-pack)
          entry    (first (filter #(= :operations (:source/category %)) resolved))]
      (is (true? (:matched? entry)))))

  (testing "entry with no source rule or category is unmatched"
    (let [resolved (sut/resolve-mapping test-mapping test-pack)
          entry    (first (filter #(= :none (:mapping/type %)) resolved))]
      (is (false? (:matched? entry))))))

;; ============================================================================
;; Report projection tests
;; ============================================================================

(deftest project-report-test
  (testing "summary counts matched and unmatched entries"
    (let [report (sut/project-report test-mapping test-pack)]
      (is (= 1 (get-in report [:summary :exact])))
      (is (= 1 (get-in report [:summary :broad])))
      (is (= 0 (get-in report [:summary :partial])))
      (is (= 0 (get-in report [:summary :none])))
      (is (= 2 (get-in report [:summary :unmatched])))))

  (testing "target-controls list has entries for each mapping entry"
    (let [report (sut/project-report test-mapping test-pack)]
      (is (= 4 (count (:target-controls report))))))

  (testing "each control entry has required fields"
    (let [report  (sut/project-report test-mapping test-pack)
          control (first (:target-controls report))]
      (is (contains? control :control))
      (is (contains? control :type))
      (is (contains? control :matched?))
      (is (contains? control :source)))))
