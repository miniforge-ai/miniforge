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
   [ai.miniforge.workflow.chain-loader :as chain-loader]))

(def spec-to-pr-versioned-path
  "chains/spec-to-pr-v1.0.0.edn")

(def spec-to-pr-latest-path
  "chains/spec-to-pr-v1.2.0.edn")

(def sdlc-to-deploy-path
  "chains/sdlc-to-deploy-v1.0.0.edn")

(def stub-resource-url
  (java.net.URL. "file:/tmp/miniforge-workflow-chain-loader-test-resource"))

(def chain-definitions
  {spec-to-pr-versioned-path
   {:chain/id :spec-to-pr
    :chain/version "1.0.0"
    :chain/description "Spec to PR"
    :chain/steps [{:workflow/id :spec-step}]}
   spec-to-pr-latest-path
   {:chain/id :spec-to-pr
    :chain/version "1.2.0"
    :chain/description "Spec to PR"
    :chain/steps [{:workflow/id :spec-step}
                  {:workflow/id :review-step}]}
   sdlc-to-deploy-path
   {:chain/id :sdlc-to-deploy
    :chain/version "1.0.0"
    :chain/description "SDLC to deploy"
    :chain/steps [{:workflow/id :deploy-step}]}})

(defn- resource-exists?
  [resource-path]
  (contains? chain-definitions resource-path))

(defn- fake-resource
  [resource-path]
  (when (resource-exists? resource-path)
    stub-resource-url))

(defn- fake-load-chain-resource
  [resource-path]
  (get chain-definitions resource-path))

(def test-chain-resource-names
  ["spec-to-pr-v1.0.0.edn" "test-chain-v1.0.0.edn"])

(def test-chain-summaries
  {"chains/spec-to-pr-v1.0.0.edn"
   {:id :spec-to-pr
    :version "1.0.0"
    :description "Spec to PR"
    :steps 1}
   "chains/test-chain-v1.0.0.edn"
   {:id :test-chain
    :version "1.0.0"
    :description "Test chain"
    :steps 2}})

(deftest load-chain-versioned-test
  (testing "loads chain by ID and version from resources"
    (with-redefs [io/resource fake-resource
                  chain-loader/load-chain-resource fake-load-chain-resource]
      (let [{:keys [chain source]} (chain-loader/load-chain :spec-to-pr "1.0.0")]
        (is (= :spec-to-pr (:chain/id chain)))
        (is (= "1.0.0" (:chain/version chain)))
        (is (= :resource source))
        (is (seq (:chain/steps chain)))))))

(deftest load-chain-latest-test
  (testing "loads latest version when version is 'latest'"
    (with-redefs [io/resource fake-resource
                  chain-loader/list-resource-names (fn [_]
                                                     ["spec-to-pr-v1.0.0.edn"
                                                      "spec-to-pr-v1.2.0.edn"])
                  chain-loader/load-chain-resource fake-load-chain-resource]
      (let [{:keys [chain path]} (chain-loader/load-chain :spec-to-pr "latest")]
        (is (= :spec-to-pr (:chain/id chain)))
        (is (= "1.2.0" (:chain/version chain)))
        (is (= spec-to-pr-latest-path path))))))

(deftest load-chain-not-found-test
  (testing "throws when chain not found"
    (is (thrown-with-msg? Exception #"not found"
          (chain-loader/load-chain :nonexistent-chain "1.0.0")))))

(deftest list-chains-test
  (testing "lists chain summaries from every resource name returned by discovery"
    (with-redefs [chain-loader/list-resource-names (fn [_] test-chain-resource-names)
                  chain-loader/parse-chain-resource #(get test-chain-summaries %)]
      (let [chains (chain-loader/list-chains)]
        (is (= [{:id :spec-to-pr
                 :version "1.0.0"
                 :description "Spec to PR"
                 :steps 1}
                {:id :test-chain
                 :version "1.0.0"
                 :description "Test chain"
                 :steps 2}]
               chains))))))

(deftest find-latest-chain-resource-test
  (testing "latest chain discovery chooses the highest matching version from discovered resources"
    (with-redefs [chain-loader/list-resource-names (fn [_]
                                                     ["spec-to-pr-v1.0.0.edn"
                                                      "spec-to-pr-v1.2.0.edn"
                                                      "sdlc-to-deploy-v1.0.0.edn"])]
      (is (= spec-to-pr-latest-path
             (chain-loader/find-latest-chain-resource :spec-to-pr))))))
