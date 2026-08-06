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
(ns ai.miniforge.execution-grant.interface-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.interface :as grant]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-07-31T00:00:00Z"))

(def ^{:stratum 0} later (Instant/parse "2026-07-31T01:00:00Z"))

(def ^{:stratum 0} much-later (Instant/parse "2026-08-01T00:00:00Z"))

(def ^{:stratum 0} repo-scope {:pr/repo "miniforge-ai/miniforge"})

(defn ^{:stratum 0} lookup-of
  "An `id -> grant` resolver over the given grants."
  [& grants]
  (let [by-id (into {} (map (juxt :grant/id identity)) grants)]
    (fn [id] (get by-id id))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} pr-scope (assoc repo-scope :pr/number 42))

(defn ^{:stratum 1} root
  "A delegable root merge grant, bounded on every axis."
  ([] (root {}))
  ([overrides]
   (grant/issue (merge {:principal "operator:chris"
                        :effect-class :effect/merge
                        :scope repo-scope
                        :constraints {:constraint/max-cost-usd 10.0
                                      :constraint/max-tokens 100000
                                      :constraint/max-count 5}
                        :delegable? true
                        :expires-at much-later}
                       overrides)
                now)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} closed-schema-test
  (let [g (root)]
    (is (grant/valid? g))
    (testing "unknown keys are rejected — a grant is contract, not convention"
      (is (not (grant/valid? (assoc g :grant/extra :x))))
      (is (not (m/validate grant/ExecutionGrant (assoc g :sneaky true)))))))

(deftest ^{:stratum 2} runtime-owns-identity-test
  (testing "a caller-supplied id cannot enter the factory"
    (let [forged (random-uuid)
          g (root {:grant/id forged})]
      (is (grant/valid? g))
      (is (not= forged (:grant/id g))
          "issue mints its own id — agent text cannot name a grant into being")))
  (testing "a fresh grant is never born revoked"
    (let [g (root {:grant/revoked-at now :grant/revocation-reason :breach/cost-exceeded})]
      (is (nil? (:grant/revoked-at g)))
      (is (nil? (:grant/revocation-reason g))))))

(deftest ^{:stratum 2} issue-rejects-malformed-test
  (testing "an unknown effect class is refused, not coerced"
    (is (anomaly/anomaly? (root {:effect-class :effect/launch-missiles}))))
  (testing "a missing expiry is refused — an unbounded grant is not a grant"
    (is (anomaly/anomaly? (root {:expires-at nil})))))

(deftest ^{:stratum 2} delegation-attenuates-test
  (let [parent (root)]
    (testing "a narrower child is issued and names its parent"
      (let [child (grant/delegate parent
                                  {:principal "agent:implementer"
                                   :scope pr-scope
                                   :constraints {:constraint/max-cost-usd 2.0
                                                 :constraint/max-tokens 1000
                                                 :constraint/max-count 1}
                                   :expires-at later}
                                  now)]
        (is (grant/valid? child))
        (is (= (:grant/id parent) (:grant/parent-id child)))
        (is (grant/attenuates? parent child))))

    (testing "raising a ceiling is refused, naming the axis"
      (let [refused (grant/delegate parent
                                    {:principal "agent:x"
                                     :scope repo-scope
                                     :constraints {:constraint/max-cost-usd 999.0}
                                     :expires-at later}
                                    now)]
        (is (anomaly/anomaly? refused))
        (is (some #(= :constraint/max-cost-usd (:attenuation/axis %))
                  (get-in refused [:anomaly/data :attenuation/violations])))))

    (testing "OMITTING a ceiling the parent set is refused — absent means unbounded"
      (let [refused (grant/delegate parent
                                    {:principal "agent:x"
                                     :scope repo-scope
                                     :constraints {:constraint/max-cost-usd 1.0}
                                     :expires-at later}
                                    now)]
        (is (anomaly/anomaly? refused)
            "a child that drops max-tokens would inherit an unlimited budget by omission")))

    (testing "widening scope is refused"
      (is (anomaly/anomaly?
           (grant/delegate parent
                           {:principal "agent:x"
                            :scope {}
                            :constraints (:grant/constraints parent)
                            :expires-at later}
                           now))))

    (testing "outliving the parent is refused"
      (is (anomaly/anomaly?
           (grant/delegate parent
                           {:principal "agent:x"
                            :scope repo-scope
                            :constraints (:grant/constraints parent)
                            :expires-at (Instant/parse "2026-12-01T00:00:00Z")}
                           now))))

    (testing "a child cannot change effect class — it inherits the parent's"
      (let [child (grant/delegate parent
                                  {:principal "agent:x"
                                   :effect-class :effect/deploy
                                   :scope repo-scope
                                   :constraints (:grant/constraints parent)
                                   :expires-at later}
                                  now)]
        (is (= :effect/merge (:grant/effect-class child)))))))

(deftest ^{:stratum 2} scope-narrowing-has-no-sentinel-hole-test
  (testing "a scope value equal to a presence sentinel cannot smuggle a dropped key"
    ;; Scope values are `any?`, so any sentinel default is a value some
    ;; scope may legitimately hold. Presence must be tested with
    ;; `contains?`, or a child could drop exactly this key undetected.
    (let [sentinel :ai.miniforge.execution-grant.attenuation/absent
          parent (root {:scope (assoc repo-scope :marker sentinel)})]
      (is (anomaly/anomaly?
           (grant/delegate parent
                           {:principal "agent:x"
                            :scope repo-scope
                            :constraints (:grant/constraints parent)
                            :expires-at later}
                           now))
          "dropping a key whose value equals the sentinel is still a widening")))

  (testing "a key bound to nil must still be carried by the child"
    (let [scope-with-nil (assoc repo-scope :branch nil)
          parent (root {:scope scope-with-nil})]
      (is (anomaly/anomaly?
           (grant/delegate parent
                           {:principal "agent:x"
                            :scope repo-scope
                            :constraints (:grant/constraints parent)
                            :expires-at later}
                           now))
          "an absent key is not the same as a key bound to nil")
      (is (grant/valid?
           (grant/delegate parent
                           {:principal "agent:x"
                            :scope scope-with-nil
                            :constraints (:grant/constraints parent)
                            :expires-at later}
                           now))
          "carrying the nil binding is a legal narrowing"))))

(deftest ^{:stratum 2} anomaly-types-are-routable-test
  (testing "a malformed parent is :invalid-input, not :unauthorized"
    (let [a (grant/delegate {:not "a grant"}
                            {:principal "agent:x" :expires-at later}
                            now)]
      (is (anomaly/anomaly? a))
      (is (= :invalid-input (:anomaly/type a))
          "bad caller data and denied authority must route apart")))

  (testing "a refused delegation from a valid parent is :unauthorized"
    (let [a (grant/delegate (root {:delegable? false})
                            {:principal "agent:x"
                             :scope repo-scope
                             :constraints {:constraint/max-cost-usd 1.0
                                           :constraint/max-tokens 10
                                           :constraint/max-count 1}
                             :expires-at later}
                            now)]
      (is (= :unauthorized (:anomaly/type a))))))

(deftest ^{:stratum 2} delegation-structural-refusals-test
  (testing "a non-delegable parent cannot be delegated from"
    (let [parent (root {:delegable? false})]
      (is (anomaly/anomaly?
           (grant/delegate parent
                           {:principal "agent:x"
                            :scope repo-scope
                            :constraints (:grant/constraints parent)
                            :expires-at later}
                           now)))))

  (testing "an already-revoked parent cannot be delegated from"
    (let [parent (grant/revoke (root) :breach/cost-exceeded now)]
      (is (anomaly/anomaly?
           (grant/delegate parent
                           {:principal "agent:x"
                            :scope repo-scope
                            :constraints (:grant/constraints parent)
                            :expires-at later}
                           now)))))

  (testing "an expired parent cannot be delegated from"
    (let [parent (root {:expires-at later})]
      (is (anomaly/anomaly?
           (grant/delegate parent
                           {:principal "agent:x"
                            :scope repo-scope
                            :constraints (:grant/constraints parent)
                            :expires-at later}
                           much-later))))))

(deftest ^{:stratum 2} revocation-preserves-the-record-test
  (let [g (root)
        r (grant/revoke g :breach/cost-exceeded now)]
    (testing "the grant stays readable after revocation"
      (is (grant/valid? r))
      (is (= (:grant/id g) (:grant/id r)))
      (is (= (:grant/principal g) (:grant/principal r)))
      (is (= :breach/cost-exceeded (:grant/revocation-reason r)))
      (is (grant/revoked? r)))
    (testing "re-revoking preserves the ORIGINAL cause and time"
      (let [again (grant/revoke r :revocation/operator much-later)]
        (is (= :breach/cost-exceeded (:grant/revocation-reason again)))
        (is (= now (:grant/revoked-at again)))))))

(deftest ^{:stratum 2} active-walks-lineage-test
  (let [parent (root)
        child (grant/delegate parent
                              {:principal "agent:implementer"
                               :scope pr-scope
                               :constraints {:constraint/max-cost-usd 2.0
                                             :constraint/max-tokens 1000
                                             :constraint/max-count 1}
                               :expires-at later}
                              now)]
    (testing "both live: the child is active"
      (is (grant/active? child now (lookup-of parent child))))

    (testing "revoking the PARENT deactivates the child, whose own stamp is still clean"
      (let [revoked-parent (grant/revoke parent :breach/cost-exceeded now)]
        (is (not (grant/revoked? child)) "the child itself was never stamped")
        (is (not (grant/active? child now (lookup-of revoked-parent child)))
            "revoking a parent must contain everything cut from it")))

    (testing "an unresolvable ancestor fails CLOSED"
      (is (not (grant/active? child now (constantly nil)))))

    (testing "the root-only arity refuses to vouch for a delegated grant"
      (is (not (grant/active? child now)))
      (is (grant/active? parent now)))

    (testing "expiry deactivates even with a clean revocation stamp"
      (is (nil? (:grant/revoked-at child)))
      (is (not (grant/active? child much-later (lookup-of parent child)))))))

(deftest ^{:stratum 2} lineage-cycle-is-bounded-test
  (testing "a cyclic chain terminates and fails closed rather than hanging"
    (let [a (root)
          b (root)
          cyclic-a (assoc a :grant/parent-id (:grant/id b))
          cyclic-b (assoc b :grant/parent-id (:grant/id cyclic-a))]
      (is (not (grant/active? cyclic-a now (lookup-of cyclic-a cyclic-b))))
      (is (some nil? (grant/ancestry cyclic-a (lookup-of cyclic-a cyclic-b)))))))
