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
(ns ai.miniforge.cli.command-lookup-test
  "PATH lookups go through `babashka.fs/which`, not the `which` binary,
   which does not exist on Windows. Each check is run with fs/which
   answering for a command and with it answering nothing; a lookup that
   still shells out to `which` disagrees with one of the two."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.backends.status :as status]
   [ai.miniforge.cli.web.github :as github]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} which-finding
  "A stand-in for fs/which that resolves only `found`."
  [found]
  (fn [cmd & _] (when (= cmd found) (str "/opt/fake/bin/" cmd))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} backend-command-lookup-test
  (testing "a command fs/which resolves is available"
    (with-redefs [fs/which (which-finding "miniforge-fake-backend")]
      (is (true? (status/check-command-available? "miniforge-fake-backend")))))
  (testing "a command fs/which does not resolve is not, even one `which` finds"
    (with-redefs [fs/which (which-finding nil)]
      (is (false? (status/check-command-available? "sh")))))
  (testing "a missing command name is not available"
    (is (false? (status/check-command-available? nil)))))

(deftest ^{:stratum 1} claude-cli-lookup-test
  (testing "claude resolved by fs/which"
    (with-redefs [fs/which (which-finding "claude")]
      (is (true? (github/check-claude-cli)))))
  (testing "claude not resolved by fs/which"
    (with-redefs [fs/which (which-finding nil)]
      (is (false? (github/check-claude-cli))))))
