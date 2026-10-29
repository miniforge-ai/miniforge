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
(ns ai.miniforge.cli.main.commands.pr-list-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.main.commands.pr :as sut]
   [ai.miniforge.cli.main.commands.shared :as shared]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} missing-gh
  "What babashka.process/sh throws when gh is not installed."
  [& _]
  (throw (java.io.IOException. "Cannot run program \"gh\": error=2, No such file or directory")))

(defn- ^{:stratum 0} gh-by-repo
  "A `process/sh` stand-in for an installed gh: `gh pr list --repo r ...`
   returns `(get results r)`."
  [results]
  (fn [& args]
    (get results (second (drop-while #(not= "--repo" %) args)))))

(def ^{:stratum 0} ^:private gh-auth-failure
  {:exit 1 :out "" :err "HTTP 401: Bad credentials (https://api.github.com/graphql)"})

(def ^{:stratum 0} ^:private gh-one-pr
  {:exit 0
   :err ""
   :out "[{\"number\":7,\"title\":\"Fix it\",\"state\":\"OPEN\",\"author\":{\"login\":\"ana\"}}]"})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-pr-list
  "Run `pr-list-cmd` for `opts` with gh installed and answering per
   `results`. Returns {:exit code-or-:no-exit :out stdout :err stderr}."
  [opts results]
  (let [exit (atom :no-exit)
        out  (java.io.StringWriter.)
        err  (java.io.StringWriter.)]
    (with-redefs [fs/which     (fn [& _] "/usr/local/bin/gh")
                  process/sh   (gh-by-repo results)
                  shared/exit! (fn [code] (reset! exit code))]
      (binding [*out* out
                *err* err]
        (sut/pr-list-cmd opts (constantly {:fleet {:repos (:repos opts)}}))))
    {:exit @exit :out (str out) :err (str err)}))

(deftest ^{:stratum 1} pr-list-without-gh-exits-1-test
  (testing "gh not on PATH: a plain error on stderr and exit 1, not a stack trace"
    (let [exit (atom :no-exit)
          err  (java.io.StringWriter.)]
      (with-redefs [fs/which     (fn [& _] nil)
                    process/sh   missing-gh
                    shared/exit! (fn [code] (reset! exit code))]
        (binding [*out* (java.io.StringWriter.)
                  *err* err]
          (sut/pr-list-cmd {:repo "owner/repo"} (constantly {}))))
      (is (= 1 @exit))
      (is (str/includes? (str err) "GitHub CLI (gh) not found on PATH")))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} pr-list-gh-query-failure-exits-1-test
  (testing "gh is installed but the query fails: gh's error on stderr and exit 1"
    (let [{:keys [exit err]} (run-pr-list {:repo "owner/repo"} {"owner/repo" gh-auth-failure})]
      (is (= 1 exit))
      (is (str/includes? err "HTTP 401: Bad credentials"))))

  (testing "one repo fails: the other repo is still listed, then exit 1"
    (let [{:keys [exit out err]} (run-pr-list {:repos ["owner/broken" "owner/fine"]}
                                              {"owner/broken" gh-auth-failure
                                               "owner/fine"   gh-one-pr})]
      (is (= 1 exit))
      (is (str/includes? err "HTTP 401: Bad credentials"))
      (is (str/includes? out "Fix it"))))

  (testing "every query succeeds: exit 0"
    (let [{:keys [exit out]} (run-pr-list {:repo "owner/fine"} {"owner/fine" gh-one-pr})]
      (is (= :no-exit exit))
      (is (str/includes? out "Fix it")))))
