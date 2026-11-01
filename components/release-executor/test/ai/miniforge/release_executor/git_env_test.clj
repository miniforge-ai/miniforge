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
(ns ai.miniforge.release-executor.git-env-test
  "The host-mode git calls act on the worktree they run in, even when this
   process's environment binds git to another repository (as a git hook's
   environment does)."
  (:require
   [ai.miniforge.release-executor.git :as git]
   [ai.miniforge.release-executor.shell :as shell]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} git!
  "Run git in `dir` with the repository-binding variables removed;
   returns trimmed stdout."
  [dir & args]
  (str/trim (str (:out (apply process/sh
                              {:dir (str dir) :env (shell/without-repository-env (System/getenv))}
                              "git" args)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} repo!
  "A repository at `dir` with one commit whose message is `message`."
  [dir message]
  (fs/create-dirs dir)
  (git! dir "init" "-q")
  (git! dir "config" "user.email" "t@example.com")
  (git! dir "config" "user.name" "t")
  (git! dir "config" "commit.gpgsign" "false")
  (spit (str (fs/path dir "a.txt")) (str message "\n"))
  (git! dir "add" "a.txt")
  (git! dir "commit" "-q" "-m" message)
  (str dir))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} git-calls-ignore-a-git-dir-bound-to-another-repository-test
  (let [base     (fs/create-temp-dir {:prefix "release-git-env-"})
        other    (repo! (fs/path base "other") "other")
        worktree (repo! (fs/path base "worktree") "init")
        remote   (str (fs/path base "remote.git"))
        bound    (assoc (into {} (System/getenv))
                        "GIT_DIR" (str (fs/path other ".git"))
                        "GIT_WORK_TREE" other
                        "GIT_INDEX_FILE" (str (fs/path other ".git" "index")))]
    (try
      (git! base "init" "-q" "--bare" remote)
      (git! worktree "remote" "add" "origin" remote)
      (git! worktree "checkout" "-q" "-b" "fix/x")
      (spit (str (fs/path worktree "a.txt")) "fixed\n")
      (git! worktree "add" "a.txt")
      (with-redefs [shell/parent-env (constantly bound)]
        (testing "the commit lands in the worktree"
          (let [result (git/commit-changes! worktree "fix: in the worktree")]
            (is (:success? result))
            (is (= "fix: in the worktree" (git! worktree "log" "-1" "--format=%s")))
            (is (= "other" (git! other "log" "-1" "--format=%s")))))
        (testing "the push sends the worktree's branch"
          (is (:success? (git/push-branch! worktree "fix/x")))
          (is (= (git! worktree "rev-parse" "HEAD") (git! remote "rev-parse" "fix/x")))))
      (finally (fs/delete-tree base)))))
