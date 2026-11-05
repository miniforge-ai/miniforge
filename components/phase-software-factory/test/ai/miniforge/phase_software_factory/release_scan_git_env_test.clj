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
(ns ai.miniforge.phase-software-factory.release-scan-git-env-test
  "Release's host git scans act on the task worktree even when this
   process's environment binds git to another repository, as a git hook's
   does: the hook exports GIT_DIR, GIT_WORK_TREE and GIT_INDEX_FILE, and a
   child git that inherits them reads the hook's repository instead of the
   one in its working directory (#2264 review)."
  (:require
   [ai.miniforge.phase-software-factory.release-scan :as release-scan]
   [ai.miniforge.phase-software-factory.release-worktree]
   [ai.miniforge.release-executor.interface :as release-executor]
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} git!
  "Run git in `dir` with a fixed identity and without the repository
   variables; return trimmed stdout. Passes :env, so the inheriting
   stand-in in with-hook-env runs it unchanged."
  [dir & args]
  (let [{:keys [exit out err]}
        (apply shell/sh "git" "-c" "user.name=t" "-c" "user.email=t@example.invalid"
               "-c" "commit.gpgsign=false"
               (concat args [:dir (str dir)
                             :env (release-executor/without-repository-env (System/getenv))]))]
    (is (zero? exit) (str "git " (str/join " " args) ": " err))
    (str/trim out)))

(defn ^{:stratum 0} hook-env
  "This process's environment as a git hook running in `other` leaves it."
  [other]
  (assoc (release-executor/without-repository-env (System/getenv))
         "GIT_DIR"        (str (fs/path other ".git"))
         "GIT_WORK_TREE"  (str other)
         "GIT_INDEX_FILE" (str (fs/path other ".git" "index"))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-hook-env
  "Call `f` with a task worktree while this process's environment is a git
   hook's in another repository. Each repository has README.md committed
   and one untracked file. A child started without an explicit :env
   inherits the hook's environment; `local-git-env` is that environment
   without the repository variables, as it is for the real one."
  [f]
  (let [base     (fs/create-temp-dir {:prefix "release-git-env-"})
        other    (str (fs/path base "other"))
        worktree (str (fs/path base "worktree"))
        env      (hook-env other)
        sh       shell/sh]
    (try
      (doseq [[dir untracked] [[other "hook-repo.txt"] [worktree "task-work.txt"]]]
        (fs/create-dirs dir)
        (git! dir "init" "-q")
        (spit (io/file dir "README.md") "# base\n")
        (git! dir "add" "README.md")
        (git! dir "commit" "-q" "-m" "base")
        (spit (io/file dir untracked) "dirty\n"))
      (with-redefs [shell/sh (fn [& args]
                               (if (some #{:env} args)
                                 (apply sh args)
                                 (apply sh (concat args [:env env]))))
                    release-executor/local-git-env
                    (fn [] (release-executor/without-repository-env env))]
        (f worktree))
      (finally (fs/delete-tree base)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} dirty-scan-reads-the-worktree-not-the-hook-repository-test
  (with-hook-env
    (fn [worktree]
      (testing "git status lists the worktree's file, not the hook repository's"
        (is (= ["task-work.txt"]
               (mapv :path (#'ai.miniforge.phase-software-factory.release-worktree/git-dirty-files
                            worktree))))))))

(deftest ^{:stratum 2} host-runner-runs-git-in-the-worktree-test
  (with-hook-env
    (fn [worktree]
      (testing "the committed-work scan's git resolves the worktree's repository"
        (let [run (release-scan/host-runner worktree)]
          (is (= (str (fs/canonicalize worktree))
                 (str (fs/canonicalize (str/trim (:out (run ["rev-parse" "--show-toplevel"])))))))
          (is (= "?? task-work.txt" (str/trim (:out (run ["status" "--porcelain"]))))))))))
