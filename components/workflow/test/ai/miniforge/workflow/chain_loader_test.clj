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
(ns ai.miniforge.workflow.chain-loader-test
  "Tests for chain definition loading."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [ai.miniforge.workflow.chain-resources :as resources]
   [ai.miniforge.workflow.chain-test-support :as support]
   [ai.miniforge.workflow.chain-loader :as chain-loader]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} spec-to-pr-versioned-path
  "chains/spec-to-pr-v1.0.0.edn")

(def ^{:stratum 0} spec-to-pr-latest-path
  "chains/spec-to-pr-v1.2.0.edn")

(def ^{:stratum 0} stub-resource-url
  (java.net.URL. "file:/tmp/miniforge-workflow-chain-loader-test-resource"))

(defn- ^{:stratum 0} definition [version]
  (assoc (support/definition :spec-to-pr version) :chain/description "Spec to PR"))

(defn- ^{:stratum 0} summary [id steps description]
  {:id id :version "1.0.0" :description description :steps steps})

(def ^{:stratum 0} test-chain-resource-names
  ["spec-to-pr-v1.0.0.edn" "test-chain-v1.0.0.edn"])

(deftest ^{:stratum 0} load-chain-not-found-test
  (testing "throws when chain not found"
    (is (thrown-with-msg? Exception #"not found"
          (chain-loader/load-chain :nonexistent-chain "1.0.0")))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} test-chain-summaries
  {"chains/spec-to-pr-v1.0.0.edn" (summary :spec-to-pr 1 "Spec to PR")
   "chains/test-chain-v1.0.0.edn" (summary :test-chain 2 "Test chain")})

(def ^{:stratum 1} chain-definitions
  {spec-to-pr-versioned-path (definition "1.0.0")
   spec-to-pr-latest-path (definition "1.2.0")})

(defn- ^{:stratum 1} fake-resource [definitions resource-path]
  (when (contains? definitions resource-path) stub-resource-url))

(deftest ^{:stratum 1} find-latest-chain-resource-test
  (testing "latest chain discovery chooses the highest matching version from discovered resources"
    (with-redefs [resources/names (fn [_]
                                                     ["spec-to-pr-v1.0.0.edn"
                                                      "spec-to-pr-v1.2.0.edn"
                                                      "sdlc-to-deploy-v1.0.0.edn"])]
      (is (= spec-to-pr-latest-path
             (chain-loader/find-latest-chain-resource :spec-to-pr))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} list-chains-test
  (testing "lists chain summaries from every resource name returned by discovery"
    (with-redefs [resources/names (constantly test-chain-resource-names)
                  resources/summary test-chain-summaries]
      (let [chains (chain-loader/list-chains)]
        (is (= [:spec-to-pr :test-chain] (mapv :id chains)))
        (is (= [1 2] (mapv :steps chains)))
        (is (= ["1.0.0" "1.0.0"] (mapv :version chains)))
        (is (= ["Spec to PR" "Test chain"] (mapv :description chains)))))))

(deftest ^{:stratum 2} load-chain-versioned-test
  (testing "loads chain by ID and version from resources"
    (with-redefs [io/resource (partial fake-resource chain-definitions)
                  resources/read-definition chain-definitions]
      (let [{:keys [chain source]} (chain-loader/load-chain :spec-to-pr "1.0.0")]
        (is (= :spec-to-pr (:chain/id chain)))
        (is (= "1.0.0" (:chain/version chain)))
        (is (= :resource source))
        (is (seq (:chain/steps chain)))))))

(deftest ^{:stratum 2} load-chain-latest-test
  (testing "loads latest version when version is 'latest'"
    (with-redefs [io/resource (partial fake-resource chain-definitions)
                  resources/names (fn [_]
                                                     ["spec-to-pr-v1.0.0.edn"
                                                      "spec-to-pr-v1.2.0.edn"])
                  resources/read-definition chain-definitions]
      (let [{:keys [chain path]} (chain-loader/load-chain :spec-to-pr "latest")]
        (is (= :spec-to-pr (:chain/id chain)))
        (is (= "1.2.0" (:chain/version chain)))
        (is (= spec-to-pr-latest-path path))))))

(comment
  (definition "1.0.0"))
