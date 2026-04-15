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

(ns ai.miniforge.cli.main.commands.etl-test
  "Unit tests for ETL CLI commands."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.main.commands.etl :as sut]
   [ai.miniforge.cli.main.commands.shared :as shared]))

;------------------------------------------------------------------------------ Layer 0: Factory helpers

(defn make-etl-opts
  "Build a minimal ETL opts map for testing."
  ([]
   (make-etl-opts {}))
  ([overrides]
   (merge {:url "https://github.com/example/repo"} overrides)))

;------------------------------------------------------------------------------ Layer 1: Tests

(deftest validate-git-url-test
  (testing "HTTPS URL is valid"
    (is (true? (sut/validate-git-url "https://github.com/org/repo"))))

  (testing "SSH URL is valid"
    (is (true? (sut/validate-git-url "ssh://git@github.com/org/repo"))))

  (testing "git@ URL is valid"
    (is (true? (sut/validate-git-url "git@github.com:org/repo"))))

  (testing "HTTP URL is valid"
    (is (true? (sut/validate-git-url "http://github.com/org/repo"))))

  (testing "random string is invalid"
    (is (false? (sut/validate-git-url "not-a-url"))))

  (testing "empty string is invalid"
    (is (false? (sut/validate-git-url "")))))

(deftest etl-repo-cmd-missing-url-test
  (testing "command exits with error when no url provided"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/etl-repo-cmd {}))
        (is @exited?)))))

(deftest etl-repo-cmd-invalid-url-test
  (testing "command exits with error for invalid URL"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/etl-repo-cmd {:url "bad-url"}))
        (is @exited?)))))
