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

(ns ai.miniforge.cli.workflow-runner.help.registry-test
  "Shape tests for the pure-data subcommand registry."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.workflow-runner.help.registry :as sut]))

(deftest registry-covers-every-workflow-runner-subcommand-test
  (testing "subcommands map contains every workflow / chain subcommand by key"
    (is (= #{:workflow-run :workflow-list :workflow-execute :workflow-status
             :workflow-cancel :chain-run :chain-list}
           (set (keys sut/subcommands))))))

(deftest every-subcommand-spec-carries-the-help-flag-test
  (testing "every registered subcommand spec includes the --help / -h flag"
    (doseq [[subcommand-key entry] sut/subcommands]
      (let [help-spec (get-in entry [:spec :help])]
        (is (= :boolean (:coerce help-spec))
            (str "subcommand " subcommand-key " must coerce --help as boolean"))
        (is (= :h (:alias help-spec))
            (str "subcommand " subcommand-key " must alias --help to -h"))))))

(deftest spec-for-returns-the-registered-flag-spec-test
  (testing "spec-for round-trips through the registry"
    (is (= (get-in sut/subcommands [:workflow-run :spec])
           (sut/spec-for :workflow-run))))
  (testing "spec-for returns nil for unknown keys (callers treat as error)"
    (is (nil? (sut/spec-for :no-such-subcommand)))))

(deftest group-listings-cover-every-child-test
  (testing "workflow group lists every workflow-* subcommand"
    (is (= #{:workflow-run :workflow-list :workflow-execute :workflow-status
             :workflow-cancel}
           (set sut/workflow-subcommand-keys))))
  (testing "chain group lists every chain-* subcommand"
    (is (= #{:chain-run :chain-list}
           (set sut/chain-subcommand-keys)))))
