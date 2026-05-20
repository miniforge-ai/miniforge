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

(ns ai.miniforge.agent.meta-protocol-test
  "Tests for the meta-agent protocol surface — specifically the
   `:intervene` decision type added in M2 of the meta-agent
   intervention spec (see
   `work/meta-agent-repair-loop-intervention.spec.edn`)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.meta-protocol :as sut]))

;------------------------------------------------------------------------------ intervene? predicate

(deftest intervene?-true-for-intervene-status-test
  (is (true? (sut/intervene? {:status :intervene}))))

(deftest intervene?-false-for-other-statuses-test
  (testing "intervene? does not collide with the existing healthy/warning/halt"
    (is (false? (sut/intervene? {:status :healthy})))
    (is (false? (sut/intervene? {:status :warning})))
    (is (false? (sut/intervene? {:status :halt})))
    (is (false? (sut/intervene? {})) "no status → not intervene")))

;------------------------------------------------------------------------------ valid-intervention?

(deftest valid-intervention?-true-for-canonical-shape-test
  (testing ":intervene + non-blank addendum + valid scope → valid"
    (is (true? (sut/valid-intervention?
                {:status :intervene
                 :intervention/addendum "tell implementer to use proxy not reify"
                 :intervention/scope :next-implement})))))

(deftest valid-intervention?-false-without-addendum-test
  (testing "missing :intervention/addendum → invalid; runner treats as :warning"
    (is (false? (sut/valid-intervention?
                 {:status :intervene
                  :intervention/scope :next-implement})))))

(deftest valid-intervention?-false-with-blank-addendum-test
  (testing "blank addendum is treated as missing — agent didn't actually decide what to inject"
    (is (false? (sut/valid-intervention?
                 {:status :intervene
                  :intervention/addendum "   \n   "
                  :intervention/scope :next-implement})))))

(deftest valid-intervention?-false-with-unknown-scope-test
  (testing "scope must be one of {:next-implement :next-N-phases :workflow}"
    (is (false? (sut/valid-intervention?
                 {:status :intervene
                  :intervention/addendum "fix"
                  :intervention/scope :next-everything})))))

(deftest valid-intervention?-requires-n-when-scope-is-next-N-phases-test
  (testing ":next-N-phases scope requires positive integer :intervention/n"
    (is (false? (sut/valid-intervention?
                 {:status :intervene
                  :intervention/addendum "fix"
                  :intervention/scope :next-N-phases}))
        ":next-N-phases without :intervention/n is malformed")
    (is (false? (sut/valid-intervention?
                 {:status :intervene
                  :intervention/addendum "fix"
                  :intervention/scope :next-N-phases
                  :intervention/n 0}))
        ":n must be positive — 0 makes no sense")
    (is (true? (sut/valid-intervention?
                {:status :intervene
                 :intervention/addendum "fix"
                 :intervention/scope :next-N-phases
                 :intervention/n 3})))))

(deftest valid-intervention?-other-scopes-ignore-n-test
  (testing ":next-implement and :workflow scopes don't require :intervention/n"
    (is (true? (sut/valid-intervention?
                {:status :intervene
                 :intervention/addendum "fix"
                 :intervention/scope :next-implement})))
    (is (true? (sut/valid-intervention?
                {:status :intervene
                 :intervention/addendum "fix"
                 :intervention/scope :workflow})))))

(deftest valid-intervention?-false-for-non-intervene-status-test
  (testing "valid-intervention? gates on :intervene status — :warning + addendum-keys is NOT a valid intervention"
    (is (false? (sut/valid-intervention?
                 {:status :warning
                  :intervention/addendum "fix"
                  :intervention/scope :next-implement})))))

;------------------------------------------------------------------------------ create-intervention constructor

(deftest create-intervention-default-scope-test
  (testing "default scope is :next-implement — one-shot is the sane default"
    (let [hc (sut/create-intervention :repair-loop-meta
                                      "fuzzy stagnation detected"
                                      "tell implementer to use proxy not reify")]
      (is (sut/intervene? hc))
      (is (sut/valid-intervention? hc))
      (is (= :next-implement (:intervention/scope hc)))
      (is (= :repair-loop-meta (:agent/id hc))))))

(deftest create-intervention-with-explicit-scope-test
  (testing "explicit :scope flows through to :intervention/scope"
    (let [hc (sut/create-intervention :repair-loop-meta "msg" "addendum"
                                      :scope :workflow)]
      (is (= :workflow (:intervention/scope hc))))))

(deftest create-intervention-attaches-n-only-when-scope-needs-it-test
  (testing ":n is included for :next-N-phases; absent for others"
    (let [bounded (sut/create-intervention :rl "m" "a" :scope :next-N-phases :n 2)
          one-shot (sut/create-intervention :rl "m" "a" :scope :next-implement :n 5)]
      (is (= 2 (:intervention/n bounded)))
      (is (sut/valid-intervention? bounded))
      (is (not (contains? one-shot :intervention/n))
          ":n is omitted when the scope doesn't need it — keeps the map shape minimal"))))

(deftest create-intervention-carries-data-test
  (testing "diagnostic data flows through via :data, not flattened into top-level"
    (let [fingerprints [#{[:blocking "src/foo.clj"]}
                       #{[:blocking "src/foo.clj"]}]
          hc (sut/create-intervention :rl "msg" "fix"
                                      :data {:review/fingerprint-history fingerprints})]
      (is (= fingerprints
             (get-in hc [:data :review/fingerprint-history]))))))

;------------------------------------------------------------------------------ Status-predicate cohabitation

(deftest decision-statuses-are-mutually-exclusive-test
  (testing "exactly one of healthy?/warning?/halt?/intervene? is true for any well-formed decision"
    (let [hc-h (sut/create-health-check :a :healthy "x")
          hc-w (sut/create-health-check :a :warning "x")
          hc-x (sut/create-health-check :a :halt "x")
          hc-i (sut/create-intervention :a "x" "addendum")]
      (is (and (sut/healthy?   hc-h) (not (sut/warning? hc-h))
               (not (sut/halt? hc-h)) (not (sut/intervene? hc-h))))
      (is (and (sut/warning?   hc-w) (not (sut/healthy? hc-w))
               (not (sut/halt? hc-w)) (not (sut/intervene? hc-w))))
      (is (and (sut/halt?      hc-x) (not (sut/healthy? hc-x))
               (not (sut/warning? hc-x)) (not (sut/intervene? hc-x))))
      (is (and (sut/intervene? hc-i) (not (sut/healthy? hc-i))
               (not (sut/warning? hc-i)) (not (sut/halt? hc-i)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.agent.meta-protocol-test)
  :leave-this-here)
