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
(ns ai.miniforge.dag-executor.protocols.impl.worktree-persist-commit-test
  "The persist commit runs with signing, hooks, and fsmonitor off: a
   scratch-worktree commit must not depend on the operator's signing agent,
   and must not run any program the shared `.git/config` names."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.dag-executor.host-git-fixtures :as fixtures]
            [ai.miniforge.dag-executor.protocols.impl.worktree :as wt]
            [ai.miniforge.dag-executor.protocols.impl.worktree.archive :as worktree-archive]
            [ai.miniforge.dag-executor.protocols.impl.worktree.archive-steps :as worktree-archive-steps]
            [ai.miniforge.dag-executor.result :as result])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private recorded-hooks
  "Hooks a persist could reach. `--no-verify` leaves the first two
   running, `checkout -B` fires `post-checkout`, and every ref update
   fires `reference-transaction`."
  ["prepare-commit-msg" "post-commit" "post-checkout" "reference-transaction"])

(def ^{:stratum 0} ^:private fsmonitor-program-name
  "File name of the recording program the shared config names as
   `core.fsmonitor`."
  "fsmonitor")

(defn- ^{:stratum 0} install-recording-program!
  "Write an executable shell script at `file` that appends its own path to
   `marker` when run."
  [^File file marker]
  (spit file (str "#!/bin/sh\necho \"$0\" >> '" marker "'\n"))
  (.setExecutable file true))

(deftest ^{:stratum 0} persist-commit-disables-signing-test
  (let [calls (atom [])]
    (with-redefs [wt/run-git (fn [& args] (swap! calls conj (vec args)) {:exit 0 :out "" :err ""})]
      (testing "the commit carries -c commit.gpgsign=false ahead of the subcommand"
        (is (result/ok? (#'worktree-archive-steps/commit-staged! "/tmp/wt" "implement phase completed")))
        (let [args (first @calls)
              i (.indexOf ^java.util.List args "commit")]
          (is (= ["-c" "commit.gpgsign=false"] (subvec args (- i 2) i)))
          (is (some #{"--no-verify"} args))))
      (testing "the hook-free options come before the subcommand too"
        (let [args (first @calls)
              i    (.indexOf ^java.util.List args "commit")]
          (is (some #{"core.hooksPath=/dev/null"} (subvec args 0 i)))
          (is (some #{"core.fsmonitor=false"} (subvec args 0 i))))))))

(deftest ^{:stratum 0} persist-commit-failure-is-a-result-test
  (with-redefs [wt/run-git (fn [& _] {:exit 128 :out "" :err "error: 1Password: failed to fill whole buffer"})]
    (let [r (#'worktree-archive-steps/commit-staged! "/tmp/wt" "m")]
      (is (result/err? r))
      (is (= :archive-commit-failed (get-in r [:error :code]))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} point-shared-config-at-recorders!
  "Install recording hooks and an fsmonitor program under `dir`, and set
   `core.hooksPath` and `core.fsmonitor` from inside `linked`, which writes
   the config it shares with its host checkout."
  [linked dir marker]
  (let [hooks (File. (str dir) "hooks")]
    (.mkdirs hooks)
    (doseq [h (conj recorded-hooks fsmonitor-program-name)]
      (install-recording-program! (File. hooks ^String h) marker))
    (fixtures/git! linked "config" "core.hooksPath" (str hooks))
    (fixtures/git! linked "config" "core.fsmonitor"
                   (str (File. hooks ^String fsmonitor-program-name)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} archive-bundle-runs-no-hook-or-fsmonitor-from-shared-config-test
  (testing "given hooks and an fsmonitor program in the shared config → the persist runs neither"
    (let [host   (fixtures/init-host-repo! (fixtures/temp-dir!))
          parent (fixtures/temp-dir!)
          linked (str (File. parent "task-worktree"))
          marker (File. parent "ran")]
      (try
        ;; Detached, so the persist also takes its `checkout -B` path.
        (fixtures/git! host "worktree" "add" "--quiet" "--detach" linked)
        (point-shared-config-at-recorders! linked parent marker)
        (spit (File. linked "task-output.txt") "task output\n")
        (let [r (worktree-archive/archive-bundle! linked {:archive-dir (str (File. parent "archive"))
                                                          :task-id     "task-1"
                                                          :base-ref    "main"})]
          (is (true? (get-in r [:data :persisted?])) (pr-str r))
          (is (not (.exists marker))
              (when (.exists marker) (slurp marker))))
        (finally
          (fixtures/git! host "worktree" "remove" "--force" linked)
          (fixtures/delete-tree! parent)
          (fixtures/delete-tree! host))))))
