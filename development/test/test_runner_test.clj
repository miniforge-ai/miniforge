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
(ns test-runner-test
  "Pins the environment strip in `test-runner`: git started through
   `run-stream!` cannot write to the repository a hook's environment
   names, and every launcher in the namespace starts its processes with
   the sanitizer's output. The first runs real git against a decoy
   repository under java.io.tmpdir; the second starts no process."
  (:require [ai.miniforge.bb-test-runner.interface :as bb-test-runner]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [test-runner :as sut]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private stripped-probe
  "A `user.name` only the stripped write sets: the config file holding it
   is the one that write reached."
  "stripped-probe")

(def ^{:stratum 0} ^:private unstripped-probe
  "A `user.name` only the unstripped control write sets."
  "unstripped-probe")

(def ^{:stratum 0} ^:private sanitized-env
  "Stands in for the sanitizer's output: a process started with exactly
   this environment got it from `sanitize-git-worktree-env`."
  {"SANITIZED" "yes"})

(def ^{:stratum 0} ^:private launchers
  "Every function in `test-runner` that starts a process, by task name."
  {"test:poly"        sut/poly-all
   "test:integration" sut/integration
   "test:conformance" sut/conformance
   "test:graalvm"     sut/graalvm
   "test:precommit"   sut/precommit-smoke})

(defn- ^{:stratum 0} base-env
  "`PATH` and `HOME` and nothing else. The tests lay `GIT_*` variables
   over this, so each one they hand to git points at the decoy — never at
   the real checkout, even when this JVM itself runs under a git hook."
  []
  (select-keys (into {} (System/getenv)) ["PATH" "HOME"]))

(defn- ^{:stratum 0} config-file
  [repo]
  (str (fs/path repo ".git" "config")))

(defn- ^{:stratum 0} git!
  "Run git in `dir` with exactly `env`; throw on a non-zero exit."
  [env dir & args]
  (let [{:keys [exit err] :as result}
        (apply p/sh {:dir (str dir) :env env :out :string :err :string}
               "git" args)]
    (when-not (zero? exit)
      (throw (ex-info "git fixture command failed"
                      {:dir (str dir) :args (vec args) :exit exit :err err})))
    result))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} hook-env
  "`base-env` plus the repository-binding variables a git hook fired in
   `repo` hands its children. A hook does not export `GIT_CONFIG`; it is
   here because it redirects a `git config` write by itself, with or
   without `GIT_DIR`."
  [repo]
  (let [git-dir (str (fs/path repo ".git"))]
    (merge (base-env)
           {"GIT_DIR"        git-dir
            "GIT_INDEX_FILE" (str (fs/path git-dir "index"))
            "GIT_PREFIX"     ""
            "GIT_CONFIG"     (config-file repo)})))

(defn- ^{:stratum 1} started-envs
  "The `:env` of each process `launcher` starts, in order. The sanitizer
   returns `sanitized-env` and both process entry points only record, so
   nothing runs and a zero exit keeps the launcher off `System/exit`."
  [launcher]
  (let [envs   (atom [])
        record (fn [opts] (swap! envs conj (get opts :env)))]
    (with-redefs [bb-test-runner/sanitize-git-worktree-env (constantly sanitized-env)
                  p/process (fn [opts & _] (record opts) (delay {:exit 0}))
                  p/sh      (fn [opts & _] (record opts) {:exit 0 :out ""})]
      (with-out-str (launcher)))
    @envs))

(defn- ^{:stratum 1} call-with-decoy-and-target
  "Call `f` with two fresh repositories under one temp root: `:decoy`
   stands in for the checkout a hook fired in, `:target` for the temp
   directory a test runs its own git in. The root is deleted afterwards."
  [f]
  (let [root   (fs/create-temp-dir {:prefix "miniforge-test-runner-"})
        decoy  (str (fs/create-dir (fs/path root "decoy")))
        target (str (fs/create-dir (fs/path root "target")))]
    (try
      (git! (base-env) decoy "init" "--quiet")
      (git! (base-env) target "init" "--quiet")
      (f {:decoy decoy :target target})
      (finally
        (fs/delete-tree root)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-run-stream-keeps-git-off-the-repository-a-hook-names
  (call-with-decoy-and-target
   (fn [{:keys [decoy target]}]
     (let [env    (hook-env decoy)
           before (slurp (config-file decoy))]
       (testing "given a hook environment aimed at the decoy → a config write started through run-stream! lands in its own directory"
         (is (zero? (sut/run-stream! {:dir target :env env}
                                     "git" "config" "user.name" stripped-probe)))
         (is (str/includes? (slurp (config-file target)) stripped-probe))
         (is (= before (slurp (config-file decoy)))))
       (testing "given the same environment unstripped → the same write reaches the decoy"
         (git! env target "config" "user.name" unstripped-probe)
         (is (str/includes? (slurp (config-file decoy)) unstripped-probe)))))))

(deftest ^{:stratum 2} test-every-launcher-starts-its-processes-with-the-sanitized-environment
  (doseq [[task launcher] launchers]
    (testing (str "given " task " with no environment passed → each process it starts gets the sanitizer's output")
      (let [envs (started-envs launcher)]
        (is (seq envs))
        (is (every? (fn [env] (= sanitized-env env)) envs))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'test-runner-test)

  :leave-this-here)
