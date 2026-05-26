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

(ns ai.miniforge.gate.capabilities-test
  "Tests for injecting mechanical tool gates into the policy-pack registry."
  (:require
   [ai.miniforge.gate.capabilities :as sut]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [clojure.test :refer [deftest is testing]]))

(deftest register-mechanical-capabilities-test
  (testing "registers the failing mechanical gates as capabilities"
    (let [available (sut/register-mechanical-capabilities!)]
      (doseq [capability-kw [:lint :syntax :no-secrets]]
        (is (contains? available capability-kw))
        (is (policy-pack/capability-available? capability-kw)
            (str capability-kw " should be registered"))
        (is (fn? (:check (policy-pack/get-capability capability-kw)))))
      (testing ":format is intentionally NOT registered — its gate never fails"
        (is (not (contains? available :format)))
        (is (not (policy-pack/capability-available? :format)))))))

(deftest no-secrets-capability-surfaces-violation-test
  (testing "a hardcoded secret surfaces as a capability violation"
    (sut/register-mechanical-capabilities!)
    (let [check    (:check (policy-pack/get-capability :no-secrets))
          artifact {:artifact/content "(def api-key \"sk-abcdef0123456789abcdef0123456789abcd\")"
                    :artifact/path "secrets.clj"}
          result   (check artifact {})]
      (is (some? result))
      (is (= :capability (:type result)))
      (is (= :no-secrets (:capability result))))))

(deftest no-secrets-capability-clean-test
  (testing "a clean artifact yields nil through the no-secrets capability"
    (sut/register-mechanical-capabilities!)
    (let [check    (:check (policy-pack/get-capability :no-secrets))
          artifact {:artifact/content "(def x 42)" :artifact/path "core.clj"}]
      (is (nil? (check artifact {}))))))

(deftest syntax-capability-surfaces-violation-test
  (testing "a syntax error surfaces as a capability violation through the gate"
    (sut/register-mechanical-capabilities!)
    (let [check    (:check (policy-pack/get-capability :syntax))
          artifact {:artifact/content "(defn broken [] 42" :artifact/path "core.clj"}
          result   (check artifact {})]
      (is (some? result))
      (is (= :capability (:type result)))
      (is (= :syntax (:capability result))))))

(deftest registration-idempotent-test
  (testing "repeated registration is safe and stays stable"
    (sut/register-mechanical-capabilities!)
    (let [before (policy-pack/list-capabilities)]
      (sut/register-mechanical-capabilities!)
      (is (= (set (filter #{:lint :format :syntax :no-secrets} before))
             (set (filter #{:lint :format :syntax :no-secrets}
                          (policy-pack/list-capabilities))))))))
