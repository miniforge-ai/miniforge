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

(ns ai.miniforge.workflow.runner-environment-test
  "Regression tests for runner-environment's base-sha capture and
   phase-boundary persistence guard behavior."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.dag-executor.result :as result]
            [ai.miniforge.workflow.runner-environment :as env]))

(defn- sh!
  "Run a shell command and throw ex-info when exit != 0."
  [& args]
  (let [r (apply shell/sh args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "shell command failed: " (pr-str args))
                      {:cmd args :exit (:exit r) :err (:err r) :out (:out r)})))
    r))

(defn- with-temp-git-repo
  "Initialise a temp git repo with one commit and pass its path to f.
   Every shell call is checked for a zero exit code — a silent failure
   would leave an unborn branch and cause `git rev-parse HEAD` to return
   the literal string 'HEAD' instead of a SHA, producing a misleading
   test failure downstream."
  [f]
  (let [dir (str (System/getProperty "java.io.tmpdir") "/m1-base-sha-"
                 (random-uuid))]
    (.mkdirs (io/file dir))
    (try
      (sh! "git" "init" "-q" "-b" "main" :dir dir)
      (sh! "git" "-C" dir "config" "user.email" "test@local")
      (sh! "git" "-C" dir "config" "user.name" "Test")
      (sh! "git" "-C" dir "config" "commit.gpgsign" "false")
      (sh! "git" "-C" dir "commit" "--allow-empty" "-m" "seed")
      (f dir)
      (finally
        (doseq [^java.io.File fp (reverse (file-seq (io/file dir)))]
          (.delete fp))))))

(deftest build-env-record-captures-base-sha-test
  ;; The watcher in M2 needs to know what SHA each in-flight workflow
  ;; was acquired at to run the `git merge-base --is-ancestor` ancestry
  ;; check against a freshly-merged PR's SHA. Pin the capture here so
  ;; a regression that drops the field surfaces at test time.
  (testing "[:environment-metadata :base-sha] is populated from worktree HEAD"
    (with-temp-git-repo
      (fn [dir]
        (let [expected-sha (-> (sh! "git" "-C" dir "rev-parse" "HEAD")
                               :out
                               str/trim)
              fns {:create-registry (constantly {})
                   :select-exec     (constantly ::stub-executor)
                   :acquire-env!    (fn [_ task-id _]
                                      (result/ok {:environment-id (str "env-" task-id)
                                                  :workdir dir
                                                  :metadata {:base-branch "main"}}))
                   :result-ok?      result/ok?
                   :result-unwrap   :data
                   :executor-type   (constantly :worktree)}
              record (env/build-env-record ::stub-executor
                                            (random-uuid)
                                            :local
                                            {}
                                            fns)]
          (is (some? record))
          (is (= dir (:worktree-path record)))
          (is (= expected-sha (get-in record [:environment-metadata :base-sha]))
              "build-env-record sets :base-sha to resolved worktree HEAD SHA")
          (is (= "main" (get-in record [:environment-metadata :base-branch]))
              "pre-existing metadata fields must survive the merge"))))))

(deftest build-env-record-omits-base-sha-when-no-git-test
  (testing "non-git worktree → :base-sha absent, no crash"
    (let [non-git-dir (str (System/getProperty "java.io.tmpdir") "/m1-no-git-"
                           (random-uuid))]
      (.mkdirs (io/file non-git-dir))
      (try
        (let [fns {:create-registry (constantly {})
                   :select-exec     (constantly ::stub)
                   :acquire-env!    (fn [_ _ _]
                                      (result/ok {:environment-id "e"
                                                  :workdir non-git-dir
                                                  :metadata {}}))
                   :result-ok?      result/ok?
                   :result-unwrap   :data
                   :executor-type   (constantly :worktree)}
              record (env/build-env-record ::stub (random-uuid) :local {} fns)]
          (is (some? record))
          (is (not (contains? (:environment-metadata record) :base-sha))
              ":base-sha must be absent (not nil) when HEAD can't be read"))
        (finally
          (.delete (io/file non-git-dir)))))))

(deftest persist-at-phase-boundary-guards-on-worktree-test
  (testing "an executor + env-id but NO :execution/worktree-path -> the persist
            never runs; it must not fall back to committing the process CWD (the
            leak that let a sandboxless run-pipeline commit into the live repo)"
    (let [calls (atom 0)]
      (with-redefs [dag/persist-workspace!
                    (fn [& _] (swap! calls inc) (result/ok {:persisted? false}))]
        (let [outcome (env/persist-workspace-at-phase-boundary!
                       {:execution/executor ::stub-executor
                        :execution/environment-id (random-uuid)}
                       {})]
          (is (nil? outcome) "no worktree -> no persist -> nil")
          (is (zero? @calls) "persist-workspace! is never invoked without a worktree")))))
  (testing "a configured worktree-path DOES run the persist"
    (let [calls (atom 0)]
      (with-redefs [dag/persist-workspace!
                    (fn [& _] (swap! calls inc) (result/ok {:persisted? false}))]
        (env/persist-workspace-at-phase-boundary!
         {:execution/executor ::stub-executor
          :execution/environment-id (random-uuid)
          :execution/worktree-path "/tmp/sandbox-worktree"}
         {})
        (is (= 1 @calls) "persist runs when a worktree is configured")))))
