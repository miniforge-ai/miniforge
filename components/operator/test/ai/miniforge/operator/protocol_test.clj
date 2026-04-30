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
;; Protocol vars are present (defprotocol generates these as classes)

(deftest protocols-defined-test
  (testing "All four documented protocols exist as defprotocol classes"
    (is (some? sut/Operator))
    (is (some? sut/PatternDetector))
    (is (some? sut/ImprovementGenerator))
    (is (some? sut/Governance))))
