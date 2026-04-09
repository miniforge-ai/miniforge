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

(ns ai.miniforge.policy-pack.taxonomy-test
  "Unit tests for the taxonomy artifact — schemas, loading, validation, and lookups.

   Covers:
   - Layer 0: Malli schema validation for Taxonomy, TaxonomyCategory, TaxonomyAlias, TaxonomyRef
   - Layer 1: Loading from classpath, validation round-trip
   - Layer 1: Lookup helpers (category-by-id, resolve-alias, category-title, category-order)
   - Layer 2: Canonical taxonomy export from mdc-compiler"
  (:require
   [clojure.test :refer [deftest testing is]]
   [malli.core]
   [ai.miniforge.policy-pack.taxonomy :as sut]
   [ai.miniforge.policy-pack.mdc-compiler :as mdc-compiler]))

;; ============================================================================
;; Test fixtures
;; ============================================================================

(def minimal-taxonomy
  {:taxonomy/id      :test/minimal
   :taxonomy/version "1.0.0"
   :taxonomy/title   "Minimal Test Taxonomy"
   :taxonomy/categories
   [{:category/id    :test.cat/alpha
     :category/code  "000-099"
     :category/title "Alpha Category"
     :category/order 0}
    {:category/id    :test.cat/beta
     :category/code  "100-199"
     :category/title "Beta Category"
     :category/order 100}]
   :taxonomy/aliases
   [{:alias/name :alpha :alias/target :test.cat/alpha}]})

;; ============================================================================
;; Layer 0 — Schema validation tests
;; ============================================================================

(deftest taxonomy-category-schema-test
  (testing "valid category passes schema"
    (is (true? (sut/valid-taxonomy?
                {:taxonomy/id :t :taxonomy/version "1" :taxonomy/title "T"
                 :taxonomy/categories
                 [{:category/id :c :category/code "000" :category/title "C" :category/order 0}]}))))

  (testing "category missing required fields fails"
    (let [result (sut/validate-taxonomy
                  {:taxonomy/id :t :taxonomy/version "1" :taxonomy/title "T"
                   :taxonomy/categories [{:category/id :c}]})]
      (is (false? (:valid? result))))))

(deftest taxonomy-ref-schema-test
  (testing "valid TaxonomyRef"
    (is (true? (malli.core/validate sut/TaxonomyRef
                                    {:taxonomy/id :miniforge/dewey
                                     :taxonomy/min-version "1.0.0"}))))

  (testing "TaxonomyRef missing version fails"
    (is (false? (malli.core/validate sut/TaxonomyRef
                                     {:taxonomy/id :miniforge/dewey})))))

(deftest full-taxonomy-schema-test
  (testing "minimal taxonomy passes validation"
    (is (true? (sut/valid-taxonomy? minimal-taxonomy))))

  (testing "taxonomy with optional description passes"
    (is (true? (sut/valid-taxonomy?
                (assoc minimal-taxonomy :taxonomy/description "A test taxonomy")))))

  (testing "taxonomy missing id fails"
    (is (false? (sut/valid-taxonomy? (dissoc minimal-taxonomy :taxonomy/id)))))

  (testing "taxonomy missing categories fails"
    (is (false? (sut/valid-taxonomy? (dissoc minimal-taxonomy :taxonomy/categories))))))

;; ============================================================================
;; Layer 1 — Lookup helper tests
;; ============================================================================

(deftest category-by-id-test
  (testing "finds category by keyword ID"
    (let [cat (sut/category-by-id minimal-taxonomy :test.cat/alpha)]
      (is (= :test.cat/alpha (:category/id cat)))
      (is (= "Alpha Category" (:category/title cat)))))

  (testing "returns nil for unknown category"
    (is (nil? (sut/category-by-id minimal-taxonomy :test.cat/unknown)))))

(deftest resolve-alias-test
  (testing "resolves known alias to target"
    (is (= :test.cat/alpha (sut/resolve-alias minimal-taxonomy :alpha))))

  (testing "returns input unchanged for unknown alias"
    (is (= :test.cat/beta (sut/resolve-alias minimal-taxonomy :test.cat/beta)))))

(deftest category-title-test
  (testing "returns title for direct category ID"
    (is (= "Alpha Category" (sut/category-title minimal-taxonomy :test.cat/alpha))))

  (testing "returns title via alias resolution"
    (is (= "Alpha Category" (sut/category-title minimal-taxonomy :alpha))))

  (testing "returns nil for unknown category"
    (is (nil? (sut/category-title minimal-taxonomy :unknown)))))

(deftest category-order-test
  (testing "returns order for known category"
    (is (= 0 (sut/category-order minimal-taxonomy :test.cat/alpha)))
    (is (= 100 (sut/category-order minimal-taxonomy :test.cat/beta))))

  (testing "returns MAX_VALUE for unknown category"
    (is (= Integer/MAX_VALUE (sut/category-order minimal-taxonomy :unknown)))))

;; ============================================================================
;; Layer 1 — Classpath loading tests
;; ============================================================================

(deftest load-canonical-taxonomy-from-classpath-test
  (testing "loads bundled miniforge taxonomy from classpath"
    (let [result (sut/load-taxonomy-from-classpath
                  "policy_pack/taxonomies/miniforge-dewey-1.0.0.edn")]
      (is (true? (:success? result)) (str "Load failed: " (:error result)))
      (when (:success? result)
        (let [taxonomy (:taxonomy result)]
          (is (= :miniforge/dewey (:taxonomy/id taxonomy)))
          (is (= "1.0.0" (:taxonomy/version taxonomy)))
          (is (= 10 (count (:taxonomy/categories taxonomy))))
          (is (= 10 (count (:taxonomy/aliases taxonomy)))))))))

;; ============================================================================
;; Layer 2 — Canonical taxonomy export tests
;; ============================================================================

(deftest export-canonical-taxonomy-test
  (testing "exported taxonomy is valid"
    (let [taxonomy (mdc-compiler/export-canonical-taxonomy)]
      (is (true? (sut/valid-taxonomy? taxonomy)))))

  (testing "exported taxonomy has correct identity"
    (let [taxonomy (mdc-compiler/export-canonical-taxonomy)]
      (is (= :miniforge/dewey (:taxonomy/id taxonomy)))
      (is (= "1.0.0" (:taxonomy/version taxonomy)))))

  (testing "exported taxonomy has 10 top-level categories"
    (let [taxonomy (mdc-compiler/export-canonical-taxonomy)]
      (is (= 10 (count (:taxonomy/categories taxonomy))))))

  (testing "exported taxonomy has 10 aliases"
    (let [taxonomy (mdc-compiler/export-canonical-taxonomy)]
      (is (= 10 (count (:taxonomy/aliases taxonomy))))))

  (testing "categories have correct keyword IDs"
    (let [taxonomy (mdc-compiler/export-canonical-taxonomy)
          cat-ids  (set (map :category/id (:taxonomy/categories taxonomy)))]
      (is (contains? cat-ids :mf.cat/foundations))
      (is (contains? cat-ids :mf.cat/languages))
      (is (contains? cat-ids :mf.cat/workflows))
      (is (contains? cat-ids :mf.cat/meta))))

  (testing "aliases resolve correctly"
    (let [taxonomy (mdc-compiler/export-canonical-taxonomy)]
      (is (= :mf.cat/foundations (sut/resolve-alias taxonomy :foundations)))
      (is (= :mf.cat/languages (sut/resolve-alias taxonomy :languages)))))

  (testing "exported taxonomy matches bundled resource"
    (let [exported (mdc-compiler/export-canonical-taxonomy)
          loaded   (:taxonomy (sut/load-taxonomy-from-classpath
                               "policy_pack/taxonomies/miniforge-dewey-1.0.0.edn"))]
      (is (= (:taxonomy/id exported) (:taxonomy/id loaded)))
      (is (= (:taxonomy/version exported) (:taxonomy/version loaded)))
      (is (= (count (:taxonomy/categories exported))
             (count (:taxonomy/categories loaded))))
      (is (= (set (map :category/id (:taxonomy/categories exported)))
             (set (map :category/id (:taxonomy/categories loaded))))))))

;; ============================================================================
;; Regression — compile-standards-pack still includes taxonomy-ref
;; ============================================================================

(deftest compiled-pack-has-taxonomy-ref-test
  (testing "compile-standards-pack produces pack with taxonomy-ref"
    (let [result (mdc-compiler/compile-standards-pack ".standards")]
      (when (:success? result)
        (let [pack (:pack result)
              ref  (:pack/taxonomy-ref pack)]
          (is (some? ref) "Pack should have :pack/taxonomy-ref")
          (is (= :miniforge/dewey (:taxonomy/id ref)))
          (is (= "1.0.0" (:taxonomy/min-version ref))))))))
