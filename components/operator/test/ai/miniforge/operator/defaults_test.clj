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

(ns ai.miniforge.operator.defaults-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.miniforge.operator.defaults :as sut]))

;------------------------------------------------------------------------------ Layer 1
;; Pattern detector defaults

(deftest pattern-detector-system-prompt-shape-test
  (testing "Pattern detector system prompt mentions all five documented pattern types"
    (let [p sut/pattern-detector-system-prompt]
      (is (string? p))
      (is (str/includes? p "repeated-failure"))
      (is (str/includes? p "performance-degradation"))
      (is (str/includes? p "resource-waste"))
      (is (str/includes? p "anti-pattern"))
      (is (str/includes? p "improvement-opportunity"))
      ;; The prompt instructs the model to return JSON only.
      (is (str/includes? p "JSON")))))

(deftest pattern-detector-defaults-shape-test
  (testing "Pattern detector defaults bundles the prompt and a max-tokens budget"
    (let [d sut/pattern-detector-defaults]
      (is (= sut/pattern-detector-system-prompt (:system-prompt d)))
      (is (pos-int? (:max-tokens d))))))

;------------------------------------------------------------------------------ Layer 1
;; Improvement generator defaults

(deftest improvement-generator-system-prompt-shape-test
  (testing "Improvement generator prompt mentions every documented improvement type"
    (let [p sut/improvement-generator-system-prompt
          types ["prompt-change" "gate-adjustment" "policy-update"
                 "rule-addition" "budget-adjustment" "workflow-modification"]]
      (is (string? p))
      (doseq [t types]
        (is (str/includes? p t)
            (str "Prompt should mention improvement type: " t)))
      (is (str/includes? p "JSON")))))

(deftest improvement-types-set-test
  (testing "improvement-types is a set containing the documented six types"
    (is (= #{:prompt-change :gate-adjustment :policy-update
             :rule-addition :budget-adjustment :workflow-modification}
           sut/improvement-types))))

(deftest improvement-generator-defaults-shape-test
  (testing "Improvement generator defaults bundles prompt, type set, and token budget"
    (let [d sut/improvement-generator-defaults]
      (is (= sut/improvement-generator-system-prompt (:system-prompt d)))
      (is (= sut/improvement-types (:improvement-types d)))
      (is (pos-int? (:max-tokens d))))))
