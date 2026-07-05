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

(ns ai.miniforge.gate.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            ;; Loads every gate-component defmethod (syntax, lint, policy, …) so
            ;; gate-registered? sees them. Without this the resolve-check below
            ;; would read every real gate as unregistered.
            [ai.miniforge.gate.interface]
            [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Unknown gate fails closed

(deftest unknown-gate-fails-closed-test
  (testing "a gate keyword with no registration resolves to a fail-closed check"
    (let [gate   (registry/get-gate :totally-unregistered-gate)
          result ((:check gate) {:content "x"} {})]
      (is (false? (:passed? result))
          "unknown gate must NOT pass through")
      (is (= :unknown-gate (-> result :errors first :type)))
      (is (nil? (:repair gate))))))

(deftest noop-gate-passes-test
  (testing ":noop is the explicit, registered pass-through"
    (let [gate   (registry/get-gate :noop)
          result ((:check gate) {:content "x"} {})]
      (is (true? (:passed? result)))
      (is (empty? (:errors result)))
      (is (contains? (registry/list-gates) :noop)))))

;------------------------------------------------------------------------------ Layer 1
;; Resolve-check

(deftest gate-registered?-distinguishes-real-from-default-test
  (testing "gate-registered? is true for a real gate, false for an unknown one"
    (is (true? (registry/gate-registered? :noop)))
    (is (true? (registry/gate-registered? :syntax)))
    (is (false? (registry/gate-registered? :totally-unregistered-gate)))
    (is (false? (registry/gate-registered? :default))
        ":default itself is not a resolvable gate")))

;; The gate names the shipped SDLC + security-compliance workflows reference in
;; their phase `:gates` vectors — all owned by the gate component. Regenerate
;; with:
;;   grep -rhoE ':gates \[[^]]*\]' components bases projects --include=*.edn \
;;     | grep -oE ':[a-z][a-z0-9-]+' | sort -u
;; and drop the deploy-owned gates (covered by phase-deployment's gates-test).
;; A gate referenced here that no longer resolves is a fail-closed footgun: a
;; workflow would halt on it at runtime. Fix by registering the gate or, for a
;; deliberate skip, referencing :noop in the workflow.
(def shipped-gate-references
  #{:syntax :format :lint :no-secrets :pre-verify-lint :tests-pass :coverage
    :policy-verify :review-approved :quality-check :policy-review :plan-complete
    :release-ready :policy-pack :noop})

(deftest shipped-gate-references-resolve-test
  (testing "every gate-owned reference in a shipped workflow resolves to a real
            registration (never the fail-closed default)"
    (doseq [gate-kw shipped-gate-references]
      (is (registry/gate-registered? gate-kw)
          (str gate-kw " is referenced by a shipped workflow but has no "
               "registered gate — it would fail closed at runtime")))))
