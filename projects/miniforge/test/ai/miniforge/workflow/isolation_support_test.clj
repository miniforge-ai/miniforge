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
(ns ai.miniforge.workflow.isolation-support-test
  "Pins the project-level isolation fixture's containment: no git command
   it runs may write to the repository the test JVM was launched from.
   Twin of the workflow brick's
   `ai.miniforge.workflow.isolation-test-support-test`, body identical;
   this project's test classpath cannot load a brick's `test` directory."
  (:require
   [ai.miniforge.workflow.isolation-support :as sut]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private linked-worktree
  "Name of the decoy launch repository's linked worktree."
  "linked")

(def ^{:stratum 0} ^:private launch-config
  "The decoy launch repository's own config. Every value differs from
   `sut/host-config`, so a host write that reached this repository shows
   in its config file."
  [["user.email" "launch-owner@example.invalid"]
   ["user.name" "Launch Owner"]
   ["commit.gpgsign" "true"]])

(def ^{:stratum 0} ^:private leak-probe
  "A `user.name` nothing else writes: finding it in a config file shows
   which file a write reached."
  "leak-probe")

(defn- ^{:stratum 0} config-text
  [repo]
  (slurp (File. (File. (str repo) ".git") "config")))

(defn- ^{:stratum 0} config-line
  "How git writes the `section.key` entry `[k v]` in a config file."
  [[k v]]
  (str (peek (str/split k #"\.")) " = " v))

(defn- ^{:stratum 0} base-env
  "`PATH` and `HOME` from the JVM's environment and nothing else. The
   tests lay `GIT_*` variables over this, so each one they hand to git
   points where the test aimed it — never at the real checkout, even if
   this JVM was itself started from a git hook."
  []
  (select-keys (sut/launch-env) ["PATH" "HOME"]))

(defn- ^{:stratum 0} hook-env
  "`base` plus the repository-binding variables git 2.48 hands a
   pre-commit hook run from the linked worktree `worktree` of `launch`.
   `GIT_DIR` is that worktree's private git dir, whose config is the
   launch repository's shared one. A hook does not export `GIT_CONFIG`;
   it redirects a `git config` write by itself, and is here so the
   fixture is tried against it as well."
  [base launch worktree]
  (let [git-dir (str launch "/.git/worktrees/" worktree)]
    (merge base
           {"GIT_DIR"        git-dir
            "GIT_INDEX_FILE" (str git-dir "/index")
            "GIT_PREFIX"     ""
            "GIT_CONFIG"     (str launch "/.git/config")})))

(defn- ^{:stratum 0} raw-git!
  "Run git in `dir` with exactly `env` — the unscrubbed call the
   fixture's `git!` must never make. Throws on a non-zero exit."
  [env dir & args]
  (let [{:keys [exit err] :as result}
        (apply shell/sh "git" "-C" (str dir) (concat args [:env env]))]
    (when-not (zero? exit)
      (throw (ex-info "raw git command failed"
                      {:dir (str dir) :args (vec args) :exit exit :err err})))
    result))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} holds-host-config?
  "True when `repo`'s config file carries every `sut/host-config` entry."
  [repo]
  (let [text (config-text repo)]
    (every? (fn [entry] (str/includes? text (config-line entry)))
            sut/host-config)))

(defn- ^{:stratum 1} call-with-launch-repo
  "Call `f` with a decoy launch repository — a stand-in for the
   developer's checkout, with a linked worktree and `launch-config` — and
   the environment a pre-commit hook in that worktree hands its children.
   One temp root holds everything and is deleted afterwards."
  [f]
  (let [root   (sut/temp-root!)
        launch (str root "/launch")
        env    (sut/launch-env)]
    (try
      (sut/init-host-repo! launch)
      (sut/git! env launch "worktree" "add" "--quiet" "-b" linked-worktree
                (str root "/" linked-worktree))
      (doseq [entry launch-config]
        (apply sut/git! env launch (sut/config-args launch entry)))
      (f {:root root :launch launch
          :env  (hook-env (base-env) launch linked-worktree)})
      (finally
        (sut/delete-tree! root)))))

(deftest ^{:stratum 1} test-git-drops-a-variable-no-list-would-name
  (let [root  (sut/temp-root!)
        trace (File. ^String root "trace.log")
        env   (assoc (base-env) "GIT_TRACE" (.getAbsolutePath trace))]
    (try
      (testing "given GIT_TRACE aimed at a file → the fixture's git writes no trace"
        (sut/git! env root "version")
        (is (not (.exists trace))))
      (testing "given the same environment unscrubbed → git writes the trace"
        (raw-git! env root "version")
        (is (.exists trace)))
      (finally
        (sut/delete-tree! root)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-fixture-under-hook-environment-leaves-launch-config-alone
  (testing "given a pre-commit hook's environment → the host repository takes the host config and the launch repository's file is unchanged"
    (call-with-launch-repo
     (fn [{:keys [root launch env]}]
       (let [before (config-text launch)
             host   (sut/init-host-repo! (str root "/host") env)]
         (is (= before (config-text launch)))
         (is (holds-host-config? host)))))))

(deftest ^{:stratum 2} test-host-config-writes-name-their-own-file
  (call-with-launch-repo
   (fn [{:keys [root launch env]}]
     (let [before (config-text launch)
           host   (sut/init-host-repo! (str root "/host"))]
       (testing "given the hook environment unscrubbed → the fixture's config writes still reach only the host repository"
         (doseq [entry sut/host-config]
           (apply raw-git! env host (sut/config-args host entry)))
         (is (= before (config-text launch))))
       (testing "given the same environment → a config write that names no file reaches the launch repository"
         (raw-git! env host "config" "user.name" leak-probe)
         (is (str/includes? (config-text launch) leak-probe)))))))
