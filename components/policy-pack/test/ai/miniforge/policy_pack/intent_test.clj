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

(ns ai.miniforge.policy-pack.intent-test
  "Unit tests for semantic intent validation (N4 §4).

   Covers:
   - Intent inference from resource counts
   - Intent validation (declared vs actual)
   - Full semantic intent check
   - Kubernetes diff parsing"
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.intent :as sut]))

;; ============================================================================
;; Intent inference tests (N4 §4.3)
;; ============================================================================

(deftest infer-intent-test
  (testing "all zeros infers :refactor"
    (is (= :refactor (sut/infer-intent {:creates 0 :updates 0 :destroys 0}))))

  (testing "creates only infers :create"
    (is (= :create (sut/infer-intent {:creates 5 :updates 0 :destroys 0}))))

  (testing "updates only infers :update"
    (is (= :update (sut/infer-intent {:creates 0 :updates 3 :destroys 0}))))

  (testing "destroys only infers :destroy"
    (is (= :destroy (sut/infer-intent {:creates 0 :updates 0 :destroys 2}))))

  (testing "creates + destroys infers :migrate"
    (is (= :migrate (sut/infer-intent {:creates 3 :updates 0 :destroys 2}))))

  (testing "creates + updates + destroys infers :mixed"
    (is (= :mixed (sut/infer-intent {:creates 1 :updates 1 :destroys 1}))))

  (testing "nil counts default to zero"
    (is (= :refactor (sut/infer-intent {:creates nil :updates nil :destroys nil})))))

;; ============================================================================
;; Intent validation tests (N4 §4.1)
;; ============================================================================

(deftest intent-matches-import-test
  (testing "IMPORT with 0 changes passes"
    (is (true? (:passed? (sut/intent-matches? :import {:creates 0 :updates 0 :destroys 0})))))

  (testing "IMPORT with creates fails"
    (let [result (sut/intent-matches? :import {:creates 3 :updates 0 :destroys 0})]
      (is (false? (:passed? result)))
      (is (= 1 (count (:violations result))))))

  (testing "IMPORT with updates fails"
    (is (false? (:passed? (sut/intent-matches? :import {:creates 0 :updates 1 :destroys 0})))))

  (testing "IMPORT with destroys fails"
    (is (false? (:passed? (sut/intent-matches? :import {:creates 0 :updates 0 :destroys 1}))))))

(deftest intent-matches-create-test
  (testing "CREATE with creates passes"
    (is (true? (:passed? (sut/intent-matches? :create {:creates 5 :updates 2 :destroys 0})))))

  (testing "CREATE with destroys fails"
    (is (false? (:passed? (sut/intent-matches? :create {:creates 5 :updates 0 :destroys 1}))))))

(deftest intent-matches-update-test
  (testing "UPDATE with only updates passes"
    (is (true? (:passed? (sut/intent-matches? :update {:creates 0 :updates 3 :destroys 0})))))

  (testing "UPDATE with creates fails"
    (is (false? (:passed? (sut/intent-matches? :update {:creates 1 :updates 3 :destroys 0}))))))

(deftest intent-matches-destroy-test
  (testing "DESTROY with only destroys passes"
    (is (true? (:passed? (sut/intent-matches? :destroy {:creates 0 :updates 0 :destroys 5})))))

  (testing "DESTROY with creates fails"
    (is (false? (:passed? (sut/intent-matches? :destroy {:creates 1 :updates 0 :destroys 5}))))))

(deftest intent-matches-refactor-test
  (testing "REFACTOR with 0 changes passes"
    (is (true? (:passed? (sut/intent-matches? :refactor {:creates 0 :updates 0 :destroys 0})))))

  (testing "REFACTOR with any changes fails"
    (is (false? (:passed? (sut/intent-matches? :refactor {:creates 1 :updates 0 :destroys 0}))))))

(deftest intent-matches-migrate-test
  (testing "MIGRATE with creates + destroys passes"
    (is (true? (:passed? (sut/intent-matches? :migrate {:creates 3 :updates 0 :destroys 2})))))

  (testing "MIGRATE with updates fails"
    (is (false? (:passed? (sut/intent-matches? :migrate {:creates 3 :updates 1 :destroys 2}))))))

;; ============================================================================
;; Full semantic intent check tests
;; ============================================================================

(deftest semantic-intent-check-test
  (testing "returns inferred intent and metadata"
    (let [result (sut/semantic-intent-check :import {:creates 0 :updates 0 :destroys 0})]
      (is (true? (:passed? result)))
      (is (= :refactor (:inferred-intent result)))
      (is (= :import (get-in result [:metadata :declared])))))

  (testing "failing check includes violations"
    (let [result (sut/semantic-intent-check :import {:creates 3 :updates 0 :destroys 0})]
      (is (false? (:passed? result)))
      (is (seq (:violations result)))
      (is (= :create (:inferred-intent result))))))

;; ============================================================================
;; Kubernetes diff parsing tests
;; ============================================================================

(deftest parse-k8s-diff-counts-test
  (testing "empty diff returns zeros"
    (is (= {:creates 0 :updates 0 :destroys 0}
           (sut/parse-k8s-diff-counts ""))))

  (testing "additions only counts as creates"
    (is (= {:creates 2 :updates 0 :destroys 0}
           (sut/parse-k8s-diff-counts "+ apiVersion: v1\n+ kind: Service\n"))))

  (testing "deletions only counts as destroys"
    (is (= {:creates 0 :updates 0 :destroys 1}
           (sut/parse-k8s-diff-counts "- replicas: 3\n"))))

  (testing "mixed additions and deletions counts as updates"
    (is (= {:creates 0 :updates 1 :destroys 0}
           (sut/parse-k8s-diff-counts "- replicas: 3\n+ replicas: 5\n"))))

  (testing "--- and +++ header lines are excluded"
    (is (= {:creates 0 :updates 0 :destroys 0}
           (sut/parse-k8s-diff-counts "--- a/deployment.yaml\n+++ b/deployment.yaml\n")))))
