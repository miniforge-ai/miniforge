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

(ns ai.miniforge.workbench-adapter.interface-test
  (:require [ai.miniforge.workbench-adapter.interface :as sut]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; The REAL builtin packs are the test corpus: the registry generator and
;; projections must hold against every shipped pack, not a toy fixture.
(def ^:private packs-dir
  (-> (io/resource "policy_pack/packs/foundations-1.0.0.pack.edn")
      io/file .getParentFile str))

(def ^:private packs (sut/load-packs! packs-dir))

(def ^:private foundations
  (first (filter #(= "miniforge/foundations" (:pack/id %)) packs)))

(def ^:private violated-rule :foundations/no-hardcoded-secrets)

(def ^:private policy-eval
  {:policy-eval/id (random-uuid)
   :policy-eval/workflow-run-id (random-uuid)
   :policy-eval/gate-id :policy
   :policy-eval/target-type :pr
   :policy-eval/target-id "miniforge/synthetic#1"
   :policy-eval/passed? false
   :policy-eval/packs-applied ["miniforge/foundations"]
   :policy-eval/violations
   [{:violation/rule-id violated-rule
     :violation/severity :critical
     :violation/category :security
     :violation/message "Hardcoded secret detected in src/x.clj"
     :violation/location "src/x.clj:10"
     :violation/remediable? false}]
   :policy-eval/evaluated-at #inst "2026-07-03T00:00:00.000-00:00"})

(def ^:private opts
  {:snapshot-id "wb-miniforge-test-0001"
   :run-id "run-test-0001"
   :generated-at "2026-07-03T00:00:00Z"
   :registry-version "2026.07.03.1"
   :variant {:experiment_id "miniforge.policy.orchestrator-sweep"
             :label "baseline"
             :model "claude-opus-4-8"
             :method "governed"}
   :source-hashes ["sha256:deadbeef"]
   :metadata {:emitter "test"}})

;; ---------------------------------------------------------------- registry

(deftest registry-generates-one-var-per-rule-plus-aggregates
  (let [registry (sut/packs->registry packs "2026.07.03.1")
        ids (set (map :id (:state_vars registry)))
        rule-count (reduce + (map (comp count :pack/rules) packs))]
    (is (= "state_var_registry/v1" (:schema_version registry)))
    (is (= "miniforge-state-vars" (:registry_id registry)))
    ;; one per rule + one pass_rate per pack + the critical aggregate
    (is (= (+ rule-count (count packs) 1) (count (:state_vars registry))))
    (is (contains? ids "miniforge.policy.foundations.no-hardcoded-secrets"))
    (is (contains? ids "miniforge.policy.foundations.pass_rate"))
    (is (contains? ids "miniforge.gate.critical_violations_block"))
    (is (>= rule-count 50) "the generated set is the large rule universe, not a toy")))

(deftest registry-gate-effects-derive-from-enforcement
  (let [registry (sut/packs->registry packs "2026.07.03.1")
        by-id (into {} (map (juxt :id identity)) (:state_vars registry))
        secrets (by-id "miniforge.policy.foundations.no-hardcoded-secrets")]
    ;; :hard-halt enforcement -> blocks_transition on fail
    (is (= "blocks_transition" (get-in secrets [:gate_effects :fail])))
    (is (= "boolean" (:value_type secrets)))
    (is (= "miniforge/foundations" (:owner secrets)))))

;; ---------------------------------------------------------------- projection

(deftest snapshot-projects-per-rule-outcomes
  (let [snapshot (sut/policy-eval->snapshot policy-eval packs opts)
        by-id (into {} (map (juxt :state_var_id identity)) (:evaluations snapshot))
        secrets (by-id "miniforge.policy.foundations.no-hardcoded-secrets")
        rule-count (count (:pack/rules foundations))]
    (is (sut/valid-snapshot? snapshot))
    ;; applied pack only: its rules + its pass_rate + the critical aggregate
    (is (= (+ rule-count 2) (count (:evaluations snapshot))))
    (testing "the violated rule fails and blocks"
      (is (= "fail" (:status secrets)))
      (is (= 0.0 (:score secrets)))
      (is (= "blocks_transition" (:gate_effect secrets)))
      (is (= "src/x.clj:10" (-> secrets :evidence_refs first :quote))))
    (testing "an un-violated rule of the applied pack passes"
      (let [other (first (dissoc by-id "miniforge.policy.foundations.no-hardcoded-secrets"
                                 "miniforge.policy.foundations.pass_rate"
                                 "miniforge.gate.critical_violations_block"))]
        (is (= "pass" (:status (val other))))
        (is (= 1.0 (:score (val other))))))
    (testing "pass rate reflects the one failure"
      (let [rate (by-id "miniforge.policy.foundations.pass_rate")]
        (is (= (double (dec rule-count)) (get-in rate [:score_components :rules_passed])))
        (is (< (:score rate) 1.0))))
    (testing "critical violation trips the cross-pack gate"
      (let [gate (by-id "miniforge.gate.critical_violations_block")]
        (is (= "fail" (:status gate)))
        (is (= "blocks_transition" (:gate_effect gate)))))))

(deftest unapplied-packs-are-omitted-not-scored
  (let [snapshot (sut/policy-eval->snapshot policy-eval packs opts)
        ids (set (map :state_var_id (:evaluations snapshot)))]
    (is (not-any? #(str/includes? % ".kubernetes.") ids)
        "rules of packs outside packs-applied emit nothing")))

(deftest provenance-and-variant-ride-the-snapshot
  (let [snapshot (sut/policy-eval->snapshot policy-eval packs opts)]
    (is (= ["sha256:deadbeef"] (:source_hashes snapshot)))
    (is (= {:emitter "test"} (:metadata snapshot)))
    (is (= "baseline" (get-in snapshot [:variant :label])))
    (is (= "2026.07.03.1" (get-in snapshot [:registry_ref :version])))))

(deftest every-emitted-var-exists-in-the-generated-registry
  ;; The registry/projection pair must never drift: everything the
  ;; projection scores is declared, generated from the same packs.
  (let [registry (sut/packs->registry packs "2026.07.03.1")
        declared (set (map :id (:state_vars registry)))
        snapshot (sut/policy-eval->snapshot policy-eval packs opts)]
    (is (every? declared (map :state_var_id (:evaluations snapshot))))))

(deftest invalid-input-rejected-at-the-boundary
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/policy-eval->snapshot {:policy-eval/passed? false} packs opts))))

(deftest confidence-is-earned-not-invented
  ;; Mechanical scanners are deterministic; 1.0 is a statement, not a vibe.
  (let [snapshot (sut/policy-eval->snapshot policy-eval packs opts)]
    (is (every? #(= 1.0 (:confidence %)) (:evaluations snapshot)))))
