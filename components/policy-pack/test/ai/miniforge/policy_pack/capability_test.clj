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

(ns ai.miniforge.policy-pack.capability-test
  "Tests for the policy-pack capability registry."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.policy-pack.capability :as sut]
   [clojure.test :refer [deftest is testing]]))

(defn- stub-check
  "A capability check that always reports a violation."
  [_artifact _context]
  {:type :capability :message "stub violation"})

;------------------------------------------------------------------------------ register / get / list / available?

(deftest register-and-query-test
  (testing "register-capability! stores a valid entry and round-trips"
    (let [entry  {:meta {:tool :stub} :check stub-check}
          result (sut/register-capability! ::round-trip entry)]
      (is (= entry result))
      (is (= entry (sut/get-capability ::round-trip)))
      (is (sut/capability-available? ::round-trip))
      (is (contains? (sut/list-capabilities) ::round-trip))))

  (testing "list-capabilities returns a set of keywords"
    (sut/register-capability! ::listed {:meta {} :check stub-check})
    (is (set? (sut/list-capabilities)))
    (is (every? keyword? (sut/list-capabilities)))))

(deftest unregistered-query-test
  (testing "get-capability returns nil for an unregistered key"
    (is (nil? (sut/get-capability ::never-registered))))
  (testing "capability-available? is false for an unregistered key"
    (is (not (sut/capability-available? ::never-registered)))))

;------------------------------------------------------------------------------ bad input -> anomaly (no throw)

(deftest bad-input-returns-anomaly-test
  (testing "non-keyword key yields an :invalid-input anomaly, not a throw"
    (let [result (sut/register-capability! "not-a-keyword"
                                           {:meta {} :check stub-check})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))))

  (testing "missing :check yields an :invalid-input anomaly"
    (let [result (sut/register-capability! ::bad-entry {:meta {}})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))))

  (testing "non-fn :check yields an :invalid-input anomaly"
    (let [result (sut/register-capability! ::bad-check
                                           {:meta {} :check :not-a-fn})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))))

  (testing "a rejected registration is not stored"
    (sut/register-capability! ::rejected {:meta {} :check :not-a-fn})
    (is (not (sut/capability-available? ::rejected)))))
