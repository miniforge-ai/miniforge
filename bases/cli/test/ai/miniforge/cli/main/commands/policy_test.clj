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

(ns ai.miniforge.cli.main.commands.policy-test
  "Unit tests for policy pack CLI commands."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [babashka.fs :as fs]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.policy :as sut]
   [ai.miniforge.cli.main.commands.shared :as shared]))

;------------------------------------------------------------------------------ Layer 0: Fixtures & factories

(def ^:dynamic *tmp-dir* nil)

(defn tmp-dir-fixture [f]
  (let [dir (str (fs/create-temp-dir {:prefix "policy-test-"}))]
    (binding [*tmp-dir* dir]
      (try
        (f)
        (finally
          (fs/delete-tree dir))))))

(use-fixtures :each tmp-dir-fixture)

(defn make-pack
  "Build a minimal policy pack for testing."
  ([]
   (make-pack {}))
  ([overrides]
   (merge {:pack/id "test-pack"
           :pack/version "1.0.0"
           :pack/description "A test policy pack"
           :pack/rules [{:rule/id "rule-1"
                         :rule/always-apply false
                         :rule/description "Test rule"}]}
          overrides)))

;------------------------------------------------------------------------------ Layer 1: Tests

(deftest policy-list-cmd-no-component-empty-dir-test
  (testing "list command shows 'no packs' when no packs available"
    (with-redefs [shared/try-resolve-fn (constantly nil)
                  app-config/home-dir (constantly *tmp-dir*)]
      (let [output (with-out-str (sut/policy-list-cmd {}))]
        (is (re-find #"(?i)no installed" output))))))

(deftest policy-list-cmd-component-results-test
  (testing "list command displays component results when available"
    (with-redefs [shared/try-resolve-fn
                  (constantly [{:pack/id "foundations-1.0.0"
                                :pack/version "1.0.0"
                                :pack/description "Core rules"}])]
      (let [output (with-out-str (sut/policy-list-cmd {}))]
        (is (.contains output "foundations-1.0.0"))))))

(deftest policy-show-cmd-missing-pack-id-test
  (testing "show command exits with error when no pack-id provided"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/policy-show-cmd {}))
        (is @exited?)))))

(deftest policy-show-cmd-not-found-test
  (testing "show command reports not found for unknown pack"
    (let [exited? (atom false)]
      (with-redefs [shared/try-resolve-fn (constantly nil)
                    app-config/home-dir (constantly *tmp-dir*)
                    shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/policy-show-cmd {:pack-id "nonexistent"}))
        (is @exited?)))))

(deftest policy-install-cmd-missing-path-test
  (testing "install command exits with error when no path provided"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/policy-install-cmd {}))
        (is @exited?)))))

(deftest policy-install-cmd-file-not-found-test
  (testing "install command exits with error when pack file doesn't exist"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/policy-install-cmd {:path "/tmp/nonexistent.pack.edn"}))
        (is @exited?)))))

(deftest policy-install-cmd-valid-pack-test
  (testing "install command copies a valid pack file"
    (let [pack (make-pack)
          src-path (str *tmp-dir* "/my-pack.pack.edn")]
      (spit src-path (pr-str pack))
      (with-redefs [app-config/home-dir (constantly *tmp-dir*)]
        (let [output (with-out-str (sut/policy-install-cmd {:path src-path}))]
          (is (.contains output "Installed"))
          (is (fs/exists? (str *tmp-dir* "/packs/my-pack.pack.edn"))))))))
