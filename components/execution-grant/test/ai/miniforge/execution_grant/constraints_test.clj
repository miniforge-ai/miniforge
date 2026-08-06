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
(ns ai.miniforge.execution-grant.constraints-test
  (:require
   [ai.miniforge.execution-grant.interface :as grant]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-07-31T00:00:00Z"))

(def ^{:stratum 0} later (Instant/parse "2026-07-31T01:00:00Z"))

(def ^{:stratum 0} much-later (Instant/parse "2026-08-01T00:00:00Z"))

(def ^{:stratum 0} effect-scope {:workflow/run-id "run-1"})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} usage
  "Usage readings for the effect named by `effect-scope`."
  ([] (usage {}))
  ([readings] (assoc readings :effect/scope effect-scope)))

(defn ^{:stratum 1} spend-grant
  ([] (spend-grant {}))
  ([overrides]
   (grant/issue (merge {:principal "agent:implementer"
                        :effect-class :effect/spend
                        :scope effect-scope
                        :constraints {:constraint/max-cost-usd 5.0
                                      :constraint/max-tokens 100000}
                        :delegable? true
                        :expires-at later}
                       overrides)
                now)))

(deftest ^{:stratum 1} legacy-budget-mapping-test
  (testing "all three spellings in the tree map to the same axes"
    ;; agent/role-defaults.edn, orchestrator+loop, and workflow/validator
    ;; each spell one concept differently — that divergence is why
    ;; ceilings move onto grants.
    (is (= {:constraint/max-tokens 100000 :constraint/max-cost-usd 5.0}
           (grant/budget->constraints {:tokens 100000 :cost-usd 5.0})))
    (is (= {:constraint/max-tokens 100000 :constraint/max-cost-usd 10.0}
           (grant/budget->constraints {:max-tokens 100000 :max-cost-usd 10.0})))
    (is (= {:constraint/max-tokens 50 :constraint/max-cost-usd 1.0}
           (grant/budget->constraints {:budget/tokens 50 :budget/cost-usd 1.0}))))

  (testing "keys with no decided meaning are dropped, not guessed at"
    (is (= {} (grant/budget->constraints {:iterations 5 :time-seconds 60})))
    (is (= {} (grant/budget->constraints {:timeout-ms 1800000})))
    (is (= {} (grant/budget->constraints {:some-future-key 1}))))

  (testing "nil values do not become ceilings of nil"
    (is (= {} (grant/budget->constraints {:tokens nil :cost-usd nil}))))

  (testing "a mapped budget is usable as grant constraints"
    (let [g (grant/issue {:principal "agent:x"
                          :effect-class :effect/spend
                          :scope {}
                          :constraints (grant/budget->constraints {:tokens 10 :cost-usd 1.0})
                          :delegable? false
                          :expires-at later}
                         now)]
      (is (grant/valid? g))
      (is (= :exceeded (:grant/outcome (grant/authorize g {:usage/tokens 11} now)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} breaches-detects-only-real-overages-test
  (let [g (spend-grant)]
    (testing "under every ceiling is no breach"
      (is (empty? (grant/breaches g {:usage/cost-usd 1.0 :usage/tokens 10}))))
    (testing "exactly ON a ceiling is not a breach — that budget was granted"
      (is (empty? (grant/breaches g {:usage/cost-usd 5.0 :usage/tokens 100000}))))
    (testing "over a ceiling reports the axis, limit, and observation"
      (let [[b :as all] (grant/breaches g {:usage/cost-usd 5.01})]
        (is (= 1 (count all)))
        (is (= :constraint/max-cost-usd (:constraint/axis b)))
        (is (= 5.0 (:constraint/limit b)))
        (is (= 5.01 (:constraint/observed b)))))
    (testing "an unbounded axis cannot be breached"
      (is (empty? (grant/breaches (spend-grant {:constraints {}})
                                  {:usage/cost-usd 9999.0}))))
    (testing "an absent usage reading is not evidence of a breach"
      (is (empty? (grant/breaches g {}))))
    (testing "a non-numeric reading breaches rather than throwing"
      ;; A crash at the check site neither denies nor allows — it takes
      ;; the effect path down. Unverifiable is denied, like everywhere
      ;; else in this component.
      (is (= [:constraint/max-cost-usd]
             (mapv :constraint/axis (grant/breaches g {:usage/cost-usd "lots"}))))
      (is (= :exceeded (:grant/outcome
                        (grant/authorize g (usage {:usage/tokens :unknown}) now)))))))

(deftest ^{:stratum 2} authorize-fails-closed-test
  (testing "no grant at all is :absent — absence is not silence"
    (is (= :absent (:grant/outcome (grant/authorize nil {} now)))))

  (testing "a revoked grant is :inactive and carries the cause"
    (let [r (grant/revoke (spend-grant) :breach/cost-exceeded now)
          result (grant/authorize r (usage) now)]
      (is (= :inactive (:grant/outcome result)))
      (is (= :breach/cost-exceeded (:grant/revocation-reason result)))))

  (testing "an expired grant is :inactive even with a clean revocation stamp"
    (let [g (spend-grant)]
      (is (nil? (:grant/revoked-at g)))
      (is (= :inactive (:grant/outcome (grant/authorize g (usage) much-later))))))

  (testing "a delegated grant with no lookup is NOT authorized"
    (let [parent (spend-grant)
          child (grant/delegate parent
                                {:principal "agent:sub"
                                 :scope effect-scope
                                 :constraints {:constraint/max-cost-usd 1.0
                                               :constraint/max-tokens 10}
                                 :expires-at later}
                                now)]
      (is (= :inactive (:grant/outcome (grant/authorize child (usage) now)))
          "an unverified grant must never read as a live one")
      (is (grant/authorized?
           (grant/authorize child (usage) now
                            (fn [id] (when (= id (:grant/id parent)) parent)))))))

  (testing "within ceilings on a live grant is :authorized"
    (is (grant/authorized? (grant/authorize (spend-grant)
                                            (usage {:usage/cost-usd 1.0})
                                            now)))))

(deftest ^{:stratum 2} authorize-enforces-scope-test
  (let [g (spend-grant)]
    (testing "an absent effect scope fails closed"
      (is (= :scope-mismatch (:grant/outcome (grant/authorize g {} now)))))
    (testing "a changed grant binding is outside the grant scope"
      (is (= :scope-mismatch
             (:grant/outcome
              (grant/authorize g
                               {:effect/scope {:workflow/run-id "run-2"}}
                               now)))))
    (testing "a malformed persisted scope denies rather than becoming unbounded"
      (let [malformed (assoc g :grant/scope nil)]
        (is (= :scope-mismatch
               (:grant/outcome (grant/authorize malformed (usage) now))))))
    (testing "extra effect bindings narrow rather than widen"
      (is (grant/authorized?
           (grant/authorize g
                            {:effect/scope (assoc effect-scope :phase :release)}
                            now))))))

(deftest ^{:stratum 2} ceiling-holds-on-every-path-test
  ;; The two-channel lesson (T3 contradiction 9): the 3h40m runaway
  ;; happened because a ceiling was wired to one of two code paths and
  ;; the other went unmetered. A ceiling that lives on the GRANT is
  ;; enforced by the same call no matter which path reaches the effect,
  ;; so there is no second path to forget to wire.
  (let [g (spend-grant)
        over (usage {:usage/cost-usd 6.0})
        path-a (grant/authorize g over now)
        path-b (grant/authorize g over now (constantly nil))]
    (is (= :exceeded (:grant/outcome path-a)))
    (is (= :exceeded (:grant/outcome path-b))
        "a second caller cannot reach the effect through an unmetered route")
    (is (= (:constraint/breaches path-a) (:constraint/breaches path-b)))))

(deftest ^{:stratum 2} recheck-at-commit-catches-what-decide-could-not-test
  (testing "a grant revoked between decide and commit fails the commit"
    (let [g (spend-grant)
          at-decide (grant/authorize g (usage {:usage/cost-usd 1.0}) now)
          revoked (grant/revoke g :breach/cost-exceeded now)
          at-commit (grant/authorize revoked (usage {:usage/cost-usd 1.0}) now)]
      (is (grant/authorized? at-decide))
      (is (not (grant/authorized? at-commit)))
      (is (= :inactive (:grant/outcome at-commit)))))

  (testing "a grant that expires between decide and commit fails the commit"
    (let [g (spend-grant)]
      (is (grant/authorized? (grant/authorize g (usage) now)))
      (is (not (grant/authorized? (grant/authorize g (usage) much-later))))))

  (testing "usage that grew past the ceiling between the two calls fails the second"
    (let [g (spend-grant)]
      (is (grant/authorized? (grant/authorize g (usage {:usage/cost-usd 4.0}) now)))
      (is (= :exceeded
             (:grant/outcome
              (grant/authorize g (usage {:usage/cost-usd 5.5}) now)))))))

(deftest ^{:stratum 2} malformed-ceiling-is-a-breach-test
  ;; A persisted grant could carry a non-numeric ceiling. The check site
  ;; must not throw on it — unverifiable is denied, on BOTH sides of the
  ;; comparison, not just the usage side.
  (let [;; `issue` REJECTS this shape — the factory is not the hole. The
        ;; hole is a grant that reached the check site without passing
        ;; through it: read back from a store, hand-assembled, or
        ;; deserialized. Build it that way deliberately.
        g (assoc-in (spend-grant) [:grant/constraints :constraint/max-cost-usd] "five")]
    (is (not (grant/valid? (grant/issue {:principal "p"
                                         :effect-class :effect/spend
                                         :scope {}
                                         :constraints {:constraint/max-cost-usd "five"}
                                         :delegable? false
                                         :expires-at later}
                                        now)))
        "the factory refuses a non-numeric ceiling outright")
    (is (not (grant/valid? g)) "and such a grant is not schema-valid")
    (is (= [:constraint/max-cost-usd]
           (mapv :constraint/axis (grant/breaches g {:usage/cost-usd 1.0})))
        "but the check site must still deny rather than throw")
    (is (= :exceeded
           (:grant/outcome
            (grant/authorize g (usage {:usage/cost-usd 1.0}) now))))))
