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
(ns ai.miniforge.policy-pack.mdc-to-pack-mapping-test.dewey-test
  "Acceptance tests for the Dewey-code → phase-set mapping (Sections 4,
   19, and 20 of work/designs/mdc-to-pack-field-mapping.edn).

   Split out of `ai.miniforge.policy-pack.mdc-to-pack-mapping-test`
   (rule 210 split train, slice 1/7). Assertions are unchanged from the
   parent namespace — only the deftest forms and their dependency on
   `mdc-to-pack-mapping-test.dewey` moved."
  (:require
   [ai.miniforge.policy-pack.mdc-to-pack-mapping-test.dewey :as dewey]
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

;; ===========================================================================
;; Section 4: Dewey-to-Phases Mapping Tests
;; ===========================================================================
(deftest ^{:stratum 0} dewey-to-phases-test
  (testing "Foundations (0-99) → all phases"
    (is (= dewey/all-phases (dewey/dewey-to-phases "001")))
    (is (= dewey/all-phases (dewey/dewey-to-phases "000")))
    (is (= dewey/all-phases (dewey/dewey-to-phases "099"))))

  (testing "Tools (100-199) → implement + review"
    (is (= #{:implement :review} (dewey/dewey-to-phases "100")))
    (is (= #{:implement :review} (dewey/dewey-to-phases "150"))))

  (testing "Languages (200-299) → implement + review"
    (is (= #{:implement :review} (dewey/dewey-to-phases "210")))
    (is (= #{:implement :review} (dewey/dewey-to-phases "200"))))

  (testing "Frameworks (300-399) → plan + implement + review"
    (is (= #{:plan :implement :review} (dewey/dewey-to-phases "300")))
    (is (= #{:plan :implement :review} (dewey/dewey-to-phases "350"))))

  (testing "Testing (400-499) → implement + verify"
    (is (= #{:implement :verify} (dewey/dewey-to-phases "400")))
    (is (= #{:implement :verify} (dewey/dewey-to-phases "450"))))

  (testing "Operations (500-599) → implement + review"
    (is (= #{:implement :review} (dewey/dewey-to-phases "500")))
    (is (= #{:implement :review} (dewey/dewey-to-phases "550"))))

  (testing "Documentation (600-699) → implement + review"
    (is (= #{:implement :review} (dewey/dewey-to-phases "600"))))

  (testing "Workflows (700-799) → all phases"
    (is (= dewey/all-phases (dewey/dewey-to-phases "700")))
    (is (= dewey/all-phases (dewey/dewey-to-phases "715"))))

  (testing "Project-specific (800-899) → implement + review"
    (is (= #{:implement :review} (dewey/dewey-to-phases "800"))))

  (testing "Meta (900-999) → empty set (never injected)"
    (is (= #{} (dewey/dewey-to-phases "900")))
    (is (= #{} (dewey/dewey-to-phases "999"))))

  (testing "Boundary values at range transitions"
    ;; End of foundations → start of tools
    (is (= dewey/all-phases (dewey/dewey-to-phases "99")))
    (is (= #{:implement :review} (dewey/dewey-to-phases "100")))
    ;; End of tools → start of languages
    (is (= #{:implement :review} (dewey/dewey-to-phases "199")))
    (is (= #{:implement :review} (dewey/dewey-to-phases "200"))))

  (testing "Dewey range coverage is contiguous from 0-999"
    (doseq [code (range 0 1000)]
      (is (set? (dewey/dewey-to-phases (str code)))
          (str "No phase set for dewey code " code)))))

(deftest ^{:stratum 0} dewey-default-phases-test
  (testing "Unparseable dewey defaults to implement + review"
    (is (= dewey/default-phases (dewey/dewey-to-phases "")))))

(deftest ^{:stratum 0} dewey-missing-defaults-to-000-test
  (testing "Missing dewey frontmatter defaults to '000' (foundation phases)"
    ;; Per edge case: "Missing dewey frontmatter" → default to '000'
    (is (= dewey/all-phases (dewey/dewey-to-phases "000")))))

;; ===========================================================================
;; Section 19: Dewey Range Completeness and Non-Overlap Tests
;; ===========================================================================
(deftest ^{:stratum 0} dewey-ranges-non-overlapping-test
  (testing "Dewey ranges do not overlap"
    (let [ranges (mapv (fn [[lo hi _]] [lo hi]) dewey/dewey-ranges)
          sorted (sort-by first ranges)]
      (doseq [[[_ hi1] [lo2 _]] (partition 2 1 sorted)]
        (is (< hi1 lo2)
            (str "Ranges overlap: ..." hi1 "] and [" lo2 "..."))))))

(deftest ^{:stratum 0} dewey-ranges-cover-0-to-999-test
  (testing "Dewey ranges cover all codes from 0 to 999"
    (let [covered (set (for [[lo hi _] dewey/dewey-ranges
                             code (range lo (inc hi))]
                         code))
          full-range (set (range 0 1000))]
      (is (= full-range covered)
          (str "Uncovered codes: " (set/difference full-range covered))))))

;; ===========================================================================
;; Section 20: Phase Injection Equivalence Tests
;; ===========================================================================
(deftest ^{:stratum 0} phase-injection-role-equivalence-test
  (testing "Foundation rules reach all agent roles"
    (is (= dewey/all-phases (dewey/dewey-to-phases "001"))))

  (testing "Workflow rules reach all agent roles"
    (is (= dewey/all-phases (dewey/dewey-to-phases "715"))))

  (testing "Language rules reach implementer and reviewer only"
    (is (= #{:implement :review} (dewey/dewey-to-phases "210"))))

  (testing "Framework rules include planner (architecture awareness)"
    (is (contains? (dewey/dewey-to-phases "300") :plan)))

  (testing "Testing rules reach implementer and verifier"
    (is (= #{:implement :verify} (dewey/dewey-to-phases "400"))))

  (testing "Meta rules reach no agents"
    (is (empty? (dewey/dewey-to-phases "900")))))
