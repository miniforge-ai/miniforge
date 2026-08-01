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
(ns ai.miniforge.gate.decide-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.gate.decide :as decide]
   [ai.miniforge.policy-pack.interface :as policy-pack]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pins {:pins/pack-revision "test@1"
           :pins/rule-ids [:r/a]
           :pins/event-watermark nil})

(defn- ^{:stratum 0} v [action & [severity]]
  (cond-> {:rule {:rule/id :r/a :rule/enforcement {:action action}}
           :message "m"}
    severity (assoc-in [:rule :rule/severity] severity)))

(deftest ^{:stratum 0} missing-artifact-reason-test
  (is (= :reason/missing-artifact (:reason/code (decide/missing-artifact-reason)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} classify+decide [violations]
  (decide/decide (policy-pack/classify-violations violations) pins))

;; ── Grant constraints as a decide() input (Ariadne 2b) ──────────────────────
(deftest ^{:stratum 1} decide-without-grant-context-is-unchanged-test
  ;; The regression that matters most: every existing caller passes no
  ;; grant, and phase gating is not an effect. The 2-arity and the
  ;; 3-arity-with-nil forms must produce exactly what today produces.
  (doseq [violations [[] [(v :warn)] [(v :hard-halt)] [(v :require-approval)]]]
    (let [classified (policy-pack/classify-violations violations)
          two-arity (decide/decide classified pins)
          explicit-nil (decide/decide classified pins nil)]
      (is (= (:envelope/decision two-arity) (:envelope/decision explicit-nil)))
      (is (= (:envelope/reasons two-arity) (:envelope/reasons explicit-nil)))
      (is (= (:envelope/obligations two-arity) (:envelope/obligations explicit-nil)))))
  (testing "an authorized grant adds nothing either"
    (let [classified (policy-pack/classify-violations [])
          authorized (decide/decide classified pins {:grant/outcome :authorized})]
      (is (= :allow (:envelope/decision authorized)))
      (is (empty? (:envelope/reasons authorized))))))

(deftest ^{:stratum 1} decide-denies-on-grant-breach-test
  (let [clean (policy-pack/classify-violations [])]
    (testing "a breached cost ceiling denies with :reason/grant-exceeded"
      (let [e (decide/decide clean pins
                             {:grant/outcome :exceeded
                              :constraint/breaches
                              [{:constraint/axis :constraint/max-cost-usd
                                :constraint/limit 5.0
                                :constraint/observed 6.0}]})]
        (is (= :deny (:envelope/decision e)))
        (is (= [:reason/grant-exceeded] (mapv :reason/code (:envelope/reasons e))))
        (is (re-find #"max-cost-usd" (:reason/detail (first (:envelope/reasons e)))))))

    (testing "no grant for a grant-requiring effect denies with :reason/grant-absent"
      (let [e (decide/decide clean pins {:grant/outcome :absent})]
        (is (= :deny (:envelope/decision e)))
        (is (= [:reason/grant-absent] (mapv :reason/code (:envelope/reasons e))))))

    (testing "a revoked grant denies and names the cause"
      (let [e (decide/decide clean pins {:grant/outcome :inactive
                                         :grant/revocation-reason :breach/cost-exceeded})]
        (is (= :deny (:envelope/decision e)))
        (is (re-find #"cost-exceeded" (:reason/detail (first (:envelope/reasons e)))))))

    (testing "an unrecognized outcome denies rather than passing silently"
      (let [e (decide/decide clean pins {:grant/outcome :something-new})]
        (is (= :deny (:envelope/decision e)))))

    (testing "every breached axis is reported, not just the first"
      (let [e (decide/decide clean pins
                             {:grant/outcome :exceeded
                              :constraint/breaches
                              [{:constraint/axis :constraint/max-cost-usd
                                :constraint/limit 5.0 :constraint/observed 6.0}
                               {:constraint/axis :constraint/max-tokens
                                :constraint/limit 10 :constraint/observed 99}]})]
        (is (= 2 (count (:envelope/reasons e))))))))

(deftest ^{:stratum 1} grant-breach-cannot-be-downgraded-test
  ;; A ceiling that denies only sometimes is not a ceiling. Even
  ;; alongside obligations that would otherwise yield
  ;; :allow-with-obligations, a grant breach must still deny.
  (let [with-warning (policy-pack/classify-violations [(v :warn)])
        e (decide/decide with-warning pins
                         {:grant/outcome :exceeded
                          :constraint/breaches
                          [{:constraint/axis :constraint/max-tokens
                            :constraint/limit 10 :constraint/observed 11}]})]
    (is (= :deny (:envelope/decision e)))
    (is (seq (:envelope/obligations e)) "the warn obligation is still recorded")))

(deftest ^{:stratum 1} malformed-grant-result-still-denies-test
  ;; grant->reasons takes plain data across a component boundary, so it
  ;; must survive a malformed producer WITHOUT falling open. Zero reasons
  ;; is an allow — an :exceeded outcome that yields none would be a
  ;; fail-open in the enforcement path.
  (let [clean (policy-pack/classify-violations [])]
    (testing ":exceeded with no breach detail still denies"
      (doseq [result [{:grant/outcome :exceeded}
                      {:grant/outcome :exceeded :constraint/breaches []}
                      {:grant/outcome :exceeded :constraint/breaches nil}]]
        (let [e (decide/decide clean pins result)]
          (is (= :deny (:envelope/decision e)) (pr-str result))
          (is (= [:reason/grant-exceeded] (mapv :reason/code (:envelope/reasons e)))))))

    (testing "a breach entry with a non-keyword axis denies rather than throwing"
      (let [e (decide/decide clean pins
                             {:grant/outcome :exceeded
                              :constraint/breaches [{:constraint/axis nil
                                                     :constraint/limit 5
                                                     :constraint/observed 6}]})]
        (is (= :deny (:envelope/decision e)))))

    (testing "an inactive outcome with a non-keyword revocation reason still denies"
      (let [e (decide/decide clean pins {:grant/outcome :inactive
                                         :grant/revocation-reason nil})]
        (is (= :deny (:envelope/decision e)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} decision-table-test
  (testing ":hard-halt denies"
    (is (= :deny (:envelope/decision (classify+decide [(v :hard-halt)])))))
  (testing ":require-approval denies with an approval obligation (1c behavior change)"
    (let [e (classify+decide [(v :require-approval)])]
      (is (= :deny (:envelope/decision e)))
      (is (= [:obligation/approval-required]
             (mapv :obligation/type (:envelope/obligations e))))))
  (testing ":warn allows with obligation"
    (let [e (classify+decide [(v :warn)])]
      (is (= :allow-with-obligations (:envelope/decision e)))
      (is (= [:obligation/warn-recorded]
             (mapv :obligation/type (:envelope/obligations e))))))
  (testing ":audit allows with obligation"
    (is (= :allow-with-obligations
           (:envelope/decision (classify+decide [(v :audit)])))))
  (testing "an off-vocabulary action DENIES - no fall-through"
    (let [e (classify+decide [(v :block)])]
      (is (= :deny (:envelope/decision e)))
      (is (= [:reason/unknown-enforcement]
             (mapv :reason/code (:envelope/reasons e))))))
  (testing "an off-scale severity DENIES"
    (let [e (classify+decide [(v :warn :wobbly)])]
      (is (= :deny (:envelope/decision e)))
      (is (= [:reason/unknown-severity]
             (mapv :reason/code (:envelope/reasons e))))))
  (testing "no violations: allow, pins carried"
    (let [e (classify+decide [])]
      (is (= :allow (:envelope/decision e)))
      (is (= "test@1" (get-in e [:envelope/pins :pins/pack-revision])))))
  (testing "worst wins across a mixed set"
    (is (= :deny (:envelope/decision
                  (classify+decide [(v :audit) (v :warn) (v :hard-halt)]))))))

(deftest ^{:stratum 2} gates->envelope-test
  (testing "policy-gate envelope reasons/obligations merge into the phase envelope"
    (let [pol (classify+decide [(v :hard-halt)])
          phase-env (decide/gates->envelope
                     {:results [{:passed? false :gate :policy-pack :envelope pol
                                 :errors [{:message "x"}]}]}
                     false)]
      (is (= :deny (:envelope/decision phase-env)))
      (is (= [:reason/rule-violation]
             (mapv :reason/code (:envelope/reasons phase-env))))
      (is (= "test@1" (get-in phase-env [:envelope/pins :pins/pack-revision])))))
  (testing "a mechanical failure contributes :reason/gate-check-failed"
    (let [phase-env (decide/gates->envelope
                     {:results [{:passed? false :gate :lint
                                 :errors [{:message "loose board"}]}]}
                     false)]
      (is (= :deny (:envelope/decision phase-env)))
      (is (= [:reason/gate-check-failed]
             (mapv :reason/code (:envelope/reasons phase-env))))))
  (testing "nil artifact with gates configured denies with :reason/missing-artifact"
    (let [phase-env (decide/gates->envelope {:results []} true)]
      (is (= :deny (:envelope/decision phase-env)))
      (is (= [:reason/missing-artifact]
             (mapv :reason/code (:envelope/reasons phase-env))))))
  (testing "all passed, no obligations: allow"
    (let [phase-env (decide/gates->envelope
                     {:results [{:passed? true :gate :lint}]} false)]
      (is (= :allow (:envelope/decision phase-env)))))
  (testing "a passing policy gate's warn obligations survive to the phase envelope"
    (let [pol (classify+decide [(v :warn)])
          phase-env (decide/gates->envelope
                     {:results [{:passed? true :gate :policy-pack :envelope pol}]}
                     false)]
      (is (= :allow-with-obligations (:envelope/decision phase-env))))))

(deftest ^{:stratum 2} allowed?-test
  (is (decide/allowed? (classify+decide [(v :warn)])))
  (is (not (decide/allowed? (classify+decide [(v :hard-halt)])))))
