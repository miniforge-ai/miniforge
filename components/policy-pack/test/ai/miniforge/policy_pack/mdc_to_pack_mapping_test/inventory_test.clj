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
(ns ai.miniforge.policy-pack.mdc-to-pack-mapping-test.inventory-test
  "Acceptance tests for the design spec's pack metadata, frontmatter
   field-mapping completeness, and complete .mdc file inventory
   (Sections 3, 15, 16, and 17 of
   work/designs/mdc-to-pack-field-mapping.edn).

   Split out of `ai.miniforge.policy-pack.mdc-to-pack-mapping-test`
   (rule 210 split train, slice 3/7). Assertions are unchanged from the
   parent namespace — only the deftest forms and their dependency on
   `mdc-to-pack-mapping-test.inventory` moved. `pack-metadata-test` and
   `all-frontmatter-fields-mapped-test` have no fixture dependency of
   their own; they stay with the inventory tests because both were
   originally grouped under the parent file's \"Design spec data\"
   section, ahead of the inventory data they now sit beside."
  (:require
   [ai.miniforge.policy-pack.mdc-to-pack-mapping-test.inventory :as inventory]
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} pack-metadata-test
  (testing "Output pack has required identity fields"
    (let [template {:pack/id          "miniforge/standards"
                    :pack/name        "Miniforge Engineering Standards"
                    :pack/author      "miniforge.ai"
                    :pack/license     "Apache-2.0"
                    :pack/trust-level :trusted
                    :pack/authority   :authority/instruction}]
      (is (string? (:pack/id template)))
      (is (string? (:pack/name template)))
      (is (string? (:pack/author template)))
      (is (= :trusted (:pack/trust-level template)))
      (is (= :authority/instruction (:pack/authority template))))))

;; ===========================================================================
;; Section 17: Field Mapping Completeness Tests
;; ===========================================================================
(deftest ^{:stratum 0} all-frontmatter-fields-mapped-test
  (testing "All known MDC frontmatter fields have defined mappings"
    (let [_known-frontmatter-fields #{"dewey" "description" "alwaysApply" "globs"}
          mapped-sources #{:filename-slug
                           [:frontmatter "description"]
                           [:frontmatter "dewey"]
                           [:frontmatter "alwaysApply"]
                           [:frontmatter "dewey" "globs"]
                           [:derived]
                           :mdc-body
                           [:mdc-body "## Agent behavior"]
                           :constant}
          ;; Verify each frontmatter field appears in at least one mapping source
          frontmatter-in-sources (set (for [src mapped-sources
                                            :when (vector? src)
                                            field (rest src)
                                            :when (string? field)]
                                        field))]
      ;; description, dewey, alwaysApply, globs should all be covered
      (is (set/subset? #{"description" "dewey" "alwaysApply" "globs"}
                       (set/union frontmatter-in-sources #{"globs"})) ;; globs is in compound source
          "All frontmatter fields must have mappings"))))

;; ===========================================================================
;; Section 15: Inventory Completeness Tests
;; ===========================================================================
(deftest ^{:stratum 0} inventory-covers-all-mdc-files-test
  (testing "Complete inventory has 21 entries (all .standards/*.mdc files + index.mdc)"
    (is (= 21 (count inventory/complete-inventory))))

  (testing "All rule IDs in inventory are unique"
    (let [ids (vals inventory/complete-inventory)]
      (is (= (count ids) (count (set ids))))))

  (testing "All rule IDs use :std/ namespace"
    (doseq [[_ rule-id] inventory/complete-inventory]
      (is (= "std" (namespace rule-id))
          (str rule-id " should use :std/ namespace")))))

(deftest ^{:stratum 0} inventory-matches-filesystem-test
  (testing "Inventory paths match actual .standards/ directory structure"
    (let [inventory-paths (set (keys inventory/complete-inventory))
          ;; Known actual files from .standards/ directory
          actual-files #{"workflows/git-worktrees.mdc"
                         "workflows/pre-commit-discipline.mdc"
                         "workflows/git-branch-management.mdc"
                         "workflows/datever.mdc"
                         "workflows/pr-documentation.mdc"
                         "workflows/pr-layering.mdc"
                         "meta/rule-format.mdc"
                         "foundations/validation-boundaries.mdc"
                         "foundations/stratified-design.mdc"
                         "foundations/simple-made-easy.mdc"
                         "foundations/result-handling.mdc"
                         "foundations/code-quality.mdc"
                         "foundations/specification-standards.mdc"
                         "foundations/localization.mdc"
                         "languages/python.mdc"
                         "languages/clojure.mdc"
                         "project/header-copyright.mdc"
                         "testing/standards.mdc"
                         "frameworks/kubernetes.mdc"
                         "frameworks/polylith.mdc"}
          ;; index.mdc is at root, not in .standards/ subdirectory
          inventory-without-index (disj inventory-paths "index.mdc")]
      (is (= actual-files inventory-without-index)
          (str "Missing from inventory: " (set/difference actual-files inventory-without-index)
               ", Extra in inventory: " (set/difference inventory-without-index actual-files))))))

;; ===========================================================================
;; Section 16: Output Pack Structure Tests
;; ===========================================================================
(deftest ^{:stratum 0} pack-structure-categories-cover-all-rules-test
  (testing "Pack categories reference all rule IDs from inventory"
    (let [category-rules #{:std/stratified-design :std/simple-made-easy
                           :std/code-quality :std/result-handling
                           :std/validation-boundaries :std/specification-standards
                           :std/localization
                           :std/clojure :std/python
                           :std/polylith :std/kubernetes
                           :std/standards
                           :std/git-branch-management :std/pre-commit-discipline
                           :std/git-worktrees :std/pr-documentation
                           :std/pr-layering :std/datever
                           :std/header-copyright
                           :std/rule-format :std/index}
          inventory-ids (set (vals inventory/complete-inventory))]
      (is (= inventory-ids category-rules)
          "Pack categories should reference exactly the inventory rule IDs"))))
