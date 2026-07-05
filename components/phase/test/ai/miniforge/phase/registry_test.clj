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

(ns ai.miniforge.phase.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.phase.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; merge-gates

(deftest merge-gates-preserves-dropped-policy-gate-test
  (testing "an override that drops a policy gate gets it re-added (appended)"
    (is (= [:tests-pass :policy-verify]
           (registry/merge-gates
            [:pre-verify-lint :tests-pass :coverage :policy-verify]
            [:tests-pass])))))

(deftest merge-gates-keeps-order-and-no-duplicate-test
  (testing "an override that keeps the policy gate is returned as-is (no dup)"
    (is (= [:tests-pass :policy-verify]
           (registry/merge-gates
            [:pre-verify-lint :tests-pass :coverage :policy-verify]
            [:tests-pass :policy-verify])))))

(deftest merge-gates-mechanical-only-default-unchanged-test
  (testing "no policy gate in the default → override is returned verbatim
            (mechanical trim/add is the author's choice)"
    (is (= [:syntax :lint :no-secrets]
           (registry/merge-gates
            [:syntax :format :lint]
            [:syntax :lint :no-secrets])))))

(deftest merge-gates-empty-override-still-restores-policy-test
  (testing "an explicitly empty override cannot disable a default policy gate"
    (is (= [:policy-review]
           (registry/merge-gates
            [:review-approved :quality-check :policy-review]
            [])))))

;------------------------------------------------------------------------------ Layer 1
;; merge-with-defaults

(def ^:private verify-like-phase :registry-test/verify-like)

(registry/register-phase-defaults!
 verify-like-phase
 {:phase verify-like-phase
  :agent nil
  :gates [:pre-verify-lint :tests-pass :coverage :policy-verify]
  :budget {:tokens 0 :iterations 3 :time-seconds 300}})

(deftest merge-with-defaults-no-gates-override-keeps-defaults-test
  (testing "a config without :gates keeps the full default gate vector"
    (is (= [:pre-verify-lint :tests-pass :coverage :policy-verify]
           (:gates (registry/merge-with-defaults
                    {:phase verify-like-phase :budget {:tokens 5}}))))))

(deftest merge-with-defaults-override-cannot-drop-policy-gate-test
  (testing "a :gates override that omits :policy-verify still enforces it —
            the quick-fix-v2 footgun (verify :gates [:tests-pass]) is closed"
    (is (= [:tests-pass :policy-verify]
           (:gates (registry/merge-with-defaults
                    {:phase verify-like-phase :gates [:tests-pass]}))))))
