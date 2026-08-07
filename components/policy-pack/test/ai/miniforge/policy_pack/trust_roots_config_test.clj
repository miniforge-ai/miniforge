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
(ns ai.miniforge.policy-pack.trust-roots-config-test
  "Tests for loading the trust-root store from configuration (N4 §8.2.1)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.signing-fixtures :as fixtures]
   [ai.miniforge.policy-pack.trust-roots :as trust-roots]
   [ai.miniforge.policy-pack.trust-roots-config :as sut]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} entry
  [key-id public-key-base64]
  {:trust-root/key-id key-id
   :trust-root/public-key public-key-base64})

;------------------------------------------------------------------------------ Layer 1

;; ============================================================================
;; Configured store
;; ============================================================================
(deftest ^{:stratum 1} load-trust-roots-test
  (let [alice (fixtures/generate-keypair)]
    (testing "the shipped resource trusts nobody"
      (is (= {} (sut/load-trust-roots nil))))

    (testing "operator entries layer on top of the shipped resource"
      (let [store (sut/load-trust-roots
                   {:policy-pack
                    {:trust-roots
                     {:trust-roots/publishers
                      [(entry "alice" (:public-key-base64 alice))]}}})]
        (is (= 32 (alength (trust-roots/resolve-key store "alice"))))))

    (testing "a user config with no trust-roots block leaves the store empty"
      (is (= {} (sut/load-trust-roots {:llm {:backend :claude}}))))

    (testing "a non-map trust-roots block throws"
      (is (thrown? clojure.lang.ExceptionInfo
                   (sut/load-trust-roots {:policy-pack {:trust-roots [:not :a :map]}}))))

    (testing "a malformed operator entry throws"
      (is (thrown? clojure.lang.ExceptionInfo
                   (sut/load-trust-roots
                    {:policy-pack
                     {:trust-roots
                      {:trust-roots/publishers [(entry "alice" "not base64!")]}}}))))))
