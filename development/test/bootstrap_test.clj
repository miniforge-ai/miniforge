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
(ns bootstrap-test
  "`bb bootstrap` must leave an outside user with a working `mf`: the CLI
   installed, and no repo hook that rejects their commits (including the
   commit miniforge's own release phase makes in the clone)."
  (:require
   [bootstrap :as sut]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} bb-task
  "The task map named `task-sym` in the repo's bb.edn."
  [task-sym]
  (get-in (edn/read-string {:default (fn [_ v] v)} (slurp "bb.edn"))
          [:tasks task-sym]))

(defn ^{:stratum 0} mentions?
  "True when `sym` appears anywhere in `form`. The EDN reader has no quote
   macro, so `(run 'install:local)` reads as the symbol 'install:local."
  [form sym]
  (boolean (some #(and (symbol? %) (= (str sym) (str/replace (str %) #"^'" "")))
                 (tree-seq coll? seq form))))

(deftest ^{:stratum 0} outside-email-gets-no-hooks-and-no-warning-test
  (let [{:keys [install-hooks? lines]} (sut/hooks-plan "dev@example.com")]
    (testing "the hook that rejects non-org commit emails is not installed"
      (is (false? install-hooks?)))
    (testing "the user is not told to change their email to @miniforge.ai"
      (is (not-any? #(str/includes? % "git config user.email") lines))
      (is (not-any? #(str/includes? % "⚠️") lines)))))

(deftest ^{:stratum 0} org-email-gets-hooks-test
  (is (true? (:install-hooks? (sut/hooks-plan "dev@miniforge.ai"))))
  (is (true? (:install-hooks? (sut/hooks-plan " dev@miniforge.ai\n")))))

(deftest ^{:stratum 0} unset-email-is-reported-test
  (let [{:keys [install-hooks? lines]} (sut/hooks-plan "")]
    (is (false? install-hooks?))
    (is (some #(str/includes? % "git config user.email") lines))))

(deftest ^{:stratum 0} mf-path-problem-test
  (testing "mf on PATH is the installed one"
    (is (nil? (sut/mf-path-problem "/h/.local/bin/mf" "/h/.local/bin/mf"))))
  (testing "mf is not on PATH"
    (is (str/includes? (sut/mf-path-problem "/h/.local/bin/mf" nil) "PATH")))
  (testing "another mf shadows the installed one"
    (is (str/includes? (sut/mf-path-problem "/h/.local/bin/mf" "/opt/homebrew/bin/mf")
                       "/opt/homebrew/bin/mf"))))

(deftest ^{:stratum 0} private-dep-test
  (testing "the coordinate is read from a deps.edn map, nil once the dependency is gone"
    (is (= {:git/url "ssh://x" :git/sha "abc"}
           (sut/private-dep {:deps {'io.github.miniforge-ai/workbench-contract
                                    {:git/url "ssh://x" :git/sha "abc"}}})))
    (is (nil? (sut/private-dep {:deps {'cheshire/cheshire {:mvn/version "5.13.0"}}}))))
  (testing "the cache path matches the Clojure CLI's gitlibs layout"
    (is (= "/g/libs/io.github.miniforge-ai/workbench-contract/abc"
           (sut/gitlibs-checkout "/g" "abc"))))
  (testing "the failure names the private repository, not a git error"
    (let [msg (sut/private-dep-message "ssh://git@github.com/miniforge-ai/workbench-contract.git")]
      (is (str/includes? msg "private"))
      (is (str/includes? msg "miniforge-ai/workbench-contract")))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} bootstrap-installs-mf-test
  (let [task (bb-task 'bootstrap)]
    (testing "bootstrap runs install:local, so `mf run` resolves after it"
      (is (or (mentions? (:depends task) 'install:local)
              (mentions? (:task task) 'install:local))))
    (testing "bootstrap decides on hooks through bootstrap/hooks-plan, not unconditionally"
      (is (mentions? (:task task) 'bootstrap/hooks-plan)))
    (testing "bootstrap checks the private dependency before building"
      (is (mentions? (:task task) 'bootstrap/check-private-dep-access!)))))
