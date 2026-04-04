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

(ns ai.miniforge.context-pack.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.context-pack.interface :as ctx]
            [ai.miniforge.context-pack.schema :as schema]
            [ai.miniforge.repo-index.interface :as repo-index]
            [malli.core :as m]))

(deftest phase-budget-test
  (testing "returns configured budgets for known phases"
    (is (= 100000 (ctx/phase-budget :implement)))
    (is (= 20000 (ctx/phase-budget :plan)))
    (is (= 60000 (ctx/phase-budget :test)))
    (is (= 40000 (ctx/phase-budget :review))))

  (testing "returns default budget for unknown phases"
    (is (= 40000 (ctx/phase-budget :unknown)))))

(deftest build-pack-basic-test
  (testing "builds a context pack with repo map and files"
    (let [idx (repo-index/build-index ".")
          ;; Use small files that fit within budget
          pack (ctx/build-pack :implement idx
                 {:files-in-scope ["workspace.edn" "claude.md"]})]
      (is (some? pack))
      (is (= :implement (:phase pack)))
      (is (= 100000 (:budget pack)))
      (is (pos? (:tokens-used pack)))
      (is (string? (:repo-map pack)))
      (is (pos? (count (:files pack))))
      (is (not (:exhausted? pack))))))

(deftest build-pack-schema-test
  (testing "pack conforms to ContextPack schema"
    (let [idx (repo-index/build-index ".")
          pack (ctx/build-pack :implement idx
                 {:files-in-scope ["workspace.edn"]})]
      (is (m/validate schema/ContextPack pack)
          (str "ContextPack schema mismatch: "
               (m/explain schema/ContextPack pack))))))

(deftest build-pack-with-search-test
  (testing "builds a pack including search results"
    (let [idx (repo-index/build-index ".")
          si (repo-index/build-search-index idx)
          pack (ctx/build-pack :implement idx
                 {:files-in-scope ["workspace.edn"]
                  :search-index si
                  :search-query "implement phase interceptor"})]
      (is (pos? (count (:search-results pack))))
      (is (pos? (:tokens-used pack))))))

(deftest budget-enforcement-test
  (testing "pack respects budget limits"
    (let [idx (repo-index/build-index ".")
          ;; Very small budget should truncate
          pack (ctx/build-pack :implement idx
                 {:files-in-scope ["deps.edn" "workspace.edn" "bb.edn"]
                  :budget 100})]
      ;; With only 100 tokens, can't fit much
      (is (<= (:tokens-used pack) 200)
          "should stay near budget (with one item overshoot possible)"))))

(deftest budget-exhaustion-test
  (testing "pack marks exhausted when budget exceeded"
    (let [idx (repo-index/build-index ".")
          pack (ctx/build-pack :implement idx
                 {:files-in-scope ["deps.edn" "workspace.edn" "bb.edn"
                                   "build.clj" "agents.md"]
                  :budget 50})]
      ;; 50 tokens is too small for repo-map + files
      (is (:exhausted? pack)))))

(deftest audit-test
  (testing "audit returns budget snapshot"
    (let [idx (repo-index/build-index ".")
          pack (ctx/build-pack :implement idx
                 {:files-in-scope ["workspace.edn"]})
          a (ctx/audit pack)]
      (is (= :implement (:phase a)))
      (is (= 100000 (:budget a)))
      (is (pos? (:tokens-used a)))
      (is (pos? (:tokens-remaining a)))
      (is (pos? (:source-count a)))
      (is (double? (:utilization a)))
      (is (not (:exhausted? a))))))

(deftest audit-schema-test
  (testing "audit conforms to BudgetAudit schema"
    (let [idx (repo-index/build-index ".")
          pack (ctx/build-pack :implement idx {:files-in-scope ["workspace.edn"]})
          a (ctx/audit pack)]
      (is (m/validate schema/BudgetAudit a)
          (str "BudgetAudit schema mismatch: "
               (m/explain schema/BudgetAudit a))))))

(deftest extend-pack-test
  (testing "extend-pack adds more content within budget"
    (let [idx (repo-index/build-index ".")
          pack (ctx/build-pack :implement idx {:files-in-scope ["workspace.edn"]})
          initial-tokens (:tokens-used pack)
          extended (ctx/extend-pack pack idx
                     {:files-in-scope ["claude.md"]})]
      (is (> (:tokens-used extended) initial-tokens))
      (is (> (count (:files extended)) (count (:files pack)))))))

(deftest tokens-remaining-test
  (testing "tokens-remaining reports correct value"
    (let [idx (repo-index/build-index ".")
          pack (ctx/build-pack :implement idx {:files-in-scope ["workspace.edn"]})]
      (is (= (- (:budget pack) (:tokens-used pack))
             (ctx/tokens-remaining pack))))))

(deftest dedup-test
  (testing "duplicate files are not double-counted"
    (let [idx (repo-index/build-index ".")
          pack (ctx/build-pack :implement idx
                 {:files-in-scope ["workspace.edn" "workspace.edn" "workspace.edn"]})]
      (is (= 1 (count (:files pack)))))))
