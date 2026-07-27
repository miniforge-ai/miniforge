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
(ns ai.miniforge.cli.workflow-runner.help-test
  "Tests for `--help` / `-h` rendering on workflow-runner CLI subcommands."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [ai.miniforge.cli.workflow-runner.help :as sut]
   [ai.miniforge.cli.workflow-runner.help.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record-call-fn
  "Factory returning [fn calls-atom]; the fn appends every dispatch
   map it sees to the atom. Lets handler-with-help tests assert
   delegation without coupling to the real subcommand handlers."
  []
  (let [calls (atom [])]
    [(fn [m]
       (swap! calls conj m)
       :cmd-fn-called)
     calls]))

;------------------------------------------------------------------------------ Layer 0 — predicate
(deftest ^{:stratum 0} help-requested?-recognizes-true-and-false-test
  (testing "truthy when :help is true"
    (is (true? (sut/help-requested? {:help true}))))
  (testing "false when :help is missing or false"
    (is (false? (sut/help-requested? {})))
    (is (false? (sut/help-requested? {:help false})))))

;; rendering
(deftest ^{:stratum 0} usage-text-renders-subcommand-and-flags-test
  (testing "every subcommand renders a usage block containing its name "
    (testing "and at least one of its declared flags"
      (doseq [[subcommand-key entry] registry/subcommands]
        (let [output (sut/usage-text entry)
              non-help-flags (remove #{:help} (keys (:spec entry)))]
          (is (str/includes? output (:subcommand entry))
              (str "expected " subcommand-key " usage to mention its name"))
          (is (str/includes? output "Usage:")
              (str "expected " subcommand-key " usage to include 'Usage:' line"))
          (when (seq non-help-flags)
            (let [flag-name (name (first non-help-flags))]
              (is (str/includes? output flag-name)
                  (str "expected " subcommand-key " usage to mention --"
                       flag-name)))))))))

(deftest ^{:stratum 0} usage-text-omits-help-flag-from-flag-table-test
  (testing "the --help flag itself is not listed inside its own usage table"
    (let [entry  (registry/entry-for :workflow-run)
          output (sut/usage-text entry)]
      (is (not (str/includes? output "--help"))))))

(deftest ^{:stratum 0} group-usage-text-lists-every-child-subcommand-test
  (testing "workflow group usage lists every workflow subcommand by leaf name"
    (let [output (sut/group-usage-text registry/workflow-group-help)]
      (is (str/includes? output "workflow"))
      (doseq [leaf ["run" "list" "execute" "status" "cancel"]]
        (is (str/includes? output leaf)
            (str "expected workflow group usage to list '" leaf "'")))))
  (testing "chain group usage lists every chain subcommand by leaf name"
    (let [output (sut/group-usage-text registry/chain-group-help)]
      (is (str/includes? output "chain"))
      (doseq [leaf ["run" "list"]]
        (is (str/includes? output leaf)
            (str "expected chain group usage to list '" leaf "'"))))))

(deftest ^{:stratum 0} handler-with-help-rejects-unknown-subcommand-key-test
  (testing "wiring an unknown subcommand-key fails fast at construction time"
    (is (thrown? Exception
                 (sut/handler-with-help :no-such-subcommand
                                        (fn [_m] :should-not-run))))))

;; group-handler
(deftest ^{:stratum 0} group-handler-prints-group-usage-test
  (testing "workflow group handler prints every workflow subcommand leaf"
    (let [handler (sut/group-handler registry/workflow-group-help)
          output  (with-out-str (handler {}))]
      (doseq [leaf ["run" "list" "execute" "status" "cancel"]]
        (is (str/includes? output leaf)
            (str "expected output to list '" leaf "'"))))))

;; per-subcommand sweep
(deftest ^{:stratum 0} print-usage!-includes-subcommand-name-for-every-registered-entry-test
  (testing "every entry in the registry renders a usable --help block"
    (doseq [[subcommand-key entry] registry/subcommands]
      (let [output (with-out-str (sut/print-usage! entry))]
        (is (str/includes? output (:subcommand entry))
            (str subcommand-key " --help output should contain its name"))))))

;------------------------------------------------------------------------------ Layer 1

;; handler-with-help delegation
(deftest ^{:stratum 1} handler-with-help-short-circuits-on-help-flag-test
  (testing "--help triggers the usage block and does not call the wrapped cmd-fn"
    (let [[cmd-fn calls] (record-call-fn)
          wrapped        (sut/handler-with-help :workflow-run cmd-fn)
          output         (with-out-str (wrapped {:opts {:help true}}))]
      (is (str/includes? output "workflow run"))
      (is (empty? @calls)
          "cmd-fn must not be invoked when --help is set"))))

(deftest ^{:stratum 1} handler-with-help-delegates-when-help-absent-test
  (testing "without --help, the wrapped cmd-fn receives the original dispatch map"
    (let [[cmd-fn calls] (record-call-fn)
          wrapped        (sut/handler-with-help :workflow-run cmd-fn)
          dispatch-map   {:opts {:workflow-id "demo"}}]
      (wrapped dispatch-map)
      (is (= [dispatch-map] @calls)
          "cmd-fn should be called once with the original dispatch map"))))
