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
(ns ai.miniforge.cli.main.commands.hook-eval-test
  "`mf hook-eval` refuses file tools that leave the worktree or reach its
   git metadata, using pr-lifecycle's guard on a real repository."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.cli.main.commands.hook-eval :as sut]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} git-repo!
  "A fresh repository with core.hooksPath set to .githooks; returns its
   root as the temp directory API gives it, which on macOS is not the
   canonical path (/var is a link to /private/var). Git runs without the
   GIT_* variables a launching git hook may export, so it cannot act on
   that repository."
  []
  (let [root (str (fs/create-temp-dir {:prefix "hook-eval-test-"}))
        env  (into {} (remove #(str/starts-with? (key %) "GIT_")) (System/getenv))]
    (doseq [args [["git" "init" "-q"] ["git" "config" "core.hooksPath" ".githooks"]]]
      (apply process/shell {:dir root :env env :out :string :err :string} args))
    (fs/create-dirs (fs/path root "src"))
    root))

(defn- ^{:stratum 0} decision
  "hook-eval's permissionDecision (\"allow\" or \"deny\") for `role`
   calling `tool` with `input` in the worktree at `root`."
  [root role tool input]
  (let [payload (json/generate-string {:tool_name tool :tool_input input})
        out     (with-out-str
                  (binding [*in* (java.io.StringReader. payload)]
                    (agent/hook-eval-stdin! (sut/hook-eval-opts {:role role :root root}))))]
    (get-in (json/parse-string out) ["hookSpecificOutput" "permissionDecision"])))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} file-tools-stay-in-the-worktree-test
  (let [root      (git-repo!)
        canonical (str (fs/canonicalize root))]
    (try
      (testing "a write inside the worktree is allowed, by either spelling of its path"
        (is (= "allow" (decision root :implementer "Write" {:file_path (str root "/src/a.clj")})))
        (is (= "allow" (decision root :implementer "Write" {:file_path (str canonical "/src/a.clj")}))))
      (testing "a write into the git dir or the configured hooks dir is refused"
        (doseq [path [".git/config" ".git/hooks/pre-commit" ".githooks/pre-commit"]]
          (is (= "deny" (decision root :implementer "Write" {:file_path (str root "/" path)})) path)))
      (testing "the MCP write tool gets the same check on its relative path"
        (is (= "deny" (decision root :implementer "mcp__context__context_write"
                                {:path ".githooks/pre-commit" :content "#!/bin/sh"}))))
      (testing "reads outside the worktree are refused"
        (doseq [path ["/etc/passwd" "~/.aws/credentials" (str root "/../outside")]]
          (is (= "deny" (decision root :releaser "Read" {:file_path path})) path)))
      (finally
        (fs/delete-tree root)))))
