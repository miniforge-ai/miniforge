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

(ns ai.miniforge.loop.messages-test
  "Tests for loop component message catalog."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.loop.messages :as messages]))

;; ============================================================================
;; Translator resolution
;; ============================================================================

(deftest translator-exists-test
  (testing "t is a function"
    (is (fn? messages/t))))

;; ============================================================================
;; Escalation messages
;; ============================================================================

(deftest escalation-context-header-test
  (testing "context header interpolates iteration count"
    (let [msg (messages/t :escalation/context-header {:iteration 3})]
      (is (str/includes? msg "3"))
      (is (str/includes? msg "attempts")))))

(deftest escalation-banner-title-test
  (testing "banner title is AGENT ESCALATION"
    (is (= "AGENT ESCALATION" (messages/t :escalation/banner-title)))))

(deftest escalation-options-test
  (testing "options header and choices resolve"
    (is (string? (messages/t :escalation/options-header)))
    (is (str/includes? (messages/t :escalation/option-hints) "hints"))
    (is (str/includes? (messages/t :escalation/option-abort) "abort"))))

(deftest escalation-termination-reason-test
  (testing "termination reason interpolates reason"
    (let [msg (messages/t :escalation/termination-reason {:reason "max-iterations"})]
      (is (str/includes? msg "max-iterations")))))

;; ============================================================================
;; Repair messages
;; ============================================================================

(deftest repair-max-attempts-exceeded-test
  (testing "max attempts message resolves"
    (let [msg (messages/t :repair/max-attempts-exceeded)]
      (is (string? msg))
      (is (str/includes? msg "attempt")))))

(deftest repair-strategies-exhausted-test
  (testing "strategies exhausted message resolves"
    (let [msg (messages/t :repair/strategies-exhausted)]
      (is (string? msg))
      (is (str/includes? msg "strategies")))))

(deftest repair-escalated-test
  (testing "escalated message resolves"
    (let [msg (messages/t :repair/escalated)]
      (is (string? msg))
      (is (str/includes? msg "escalat")))))
