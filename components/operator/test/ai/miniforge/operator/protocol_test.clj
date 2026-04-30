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

(ns ai.miniforge.operator.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.operator.protocol :as sut]))

;------------------------------------------------------------------------------ Layer 1
;; Closed-set enums declared by the protocol namespace

(deftest signal-types-stable-test
  (testing "signal-types declares the documented closed set of observation signals"
    (is (= #{:workflow-complete :workflow-failed :phase-rollback
             :repeated-failure :repair-pattern :human-override
             :budget-exceeded :quality-regression}
           sut/signal-types))))

(deftest improvement-types-stable-test
  (testing "improvement-types declares the documented six improvement actions"
    (is (= #{:prompt-change :gate-adjustment :policy-update
             :rule-addition :budget-adjustment :workflow-modification}
           sut/improvement-types))))

;------------------------------------------------------------------------------ Layer 1
;; Protocol vars are present.
;;
;; defprotocol creates a Clojure *var* (the value here) bound to a map that
;; describes the protocol's methods. It also generates a Java interface as a
;; class side-effect, but the var is the canonical entry point for callers
;; using satisfies? / extend-protocol. We assert the var is defined and that
;; its protocol map declares the documented methods.

(deftest protocols-defined-test
  (testing "All four documented protocols are defined as protocol vars
            and declare the documented methods on each."
    (is (map? sut/Operator))
    (is (= #{:observe-signal :get-signals :analyze-patterns :propose-improvement
             :get-proposals :apply-improvement :reject-improvement}
           (set (keys (:sigs sut/Operator)))))
    (is (map? sut/PatternDetector))
    (is (= #{:detect :get-pattern-types}
           (set (keys (:sigs sut/PatternDetector)))))
    (is (map? sut/ImprovementGenerator))
    (is (= #{:generate-improvements :get-supported-patterns}
           (set (keys (:sigs sut/ImprovementGenerator)))))
    (is (map? sut/Governance))
    (is (= #{:requires-approval? :can-auto-apply? :get-approval-policy}
           (set (keys (:sigs sut/Governance)))))))
