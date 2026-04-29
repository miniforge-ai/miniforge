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

(ns ai.miniforge.cli.worktree-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is]]
   [ai.miniforge.cli.worktree :as sut]))

(defn- delete-tree!
  [path]
  (when (fs/exists? path)
    (doseq [entry (reverse (file-seq (fs/file path)))]
      (fs/delete entry))))

(deftest worktree-root-prefers-nearest-git-marker-test
  (let [tmp (fs/create-temp-dir {:prefix "miniforge-worktree-root-"})
        repo-root (fs/path tmp "repo")
        nested-worktree (fs/path repo-root ".claude" "worktrees" "agent-123")
        nested-src (fs/path nested-worktree "components" "sample")]
    (try
      (fs/create-dirs repo-root)
      (fs/create-dirs nested-src)
      (spit (str (fs/path repo-root ".git")) "gitdir: /tmp/repo.git\n")
      (spit (str (fs/path nested-worktree ".git")) "gitdir: /tmp/worktree.git\n")
      (is (= (str (fs/canonicalize nested-worktree))
             (sut/worktree-root (str nested-src))))
      (finally
        (delete-tree! tmp)))))

(deftest worktree-root-finds-parent-repo-when-no-child-marker-test
  (let [tmp (fs/create-temp-dir {:prefix "miniforge-worktree-parent-"})
        repo-root (fs/path tmp "repo")
        nested-src (fs/path repo-root "components" "sample")]
    (try
      (fs/create-dirs repo-root)
      (fs/create-dirs nested-src)
      (spit (str (fs/path repo-root ".git")) "gitdir: /tmp/repo.git\n")
      (is (= (str (fs/canonicalize repo-root))
             (sut/worktree-root (str nested-src))))
      (finally
        (delete-tree! tmp)))))

(deftest materialize-execution-worktree-reuses-existing-checkout-test
  (let [tmp (fs/create-temp-dir {:prefix "miniforge-worktree-materialized-"})
        target (fs/path tmp "target")]
    (try
      (fs/create-dirs target)
      (spit (str (fs/path target ".git")) "gitdir: /tmp/reused.git\n")
      (is (= (str (fs/absolutize target))
             (sut/materialize-execution-worktree! "/tmp/source" (str target))))
      (finally
        (delete-tree! tmp)))))

(deftest materialize-execution-worktree-creates-detached-worktree-test
  (let [tmp (fs/create-temp-dir {:prefix "miniforge-worktree-create-"})
        target (fs/path tmp "target")
        calls (atom [])]
    (try
      (with-redefs [sut/worktree-root (fn [_] "/tmp/source-repo")
                    shell/sh
                    (fn [& args]
                      (reset! calls args)
                      {:exit 0 :out "" :err ""})]
        (is (= (str (fs/absolutize target))
               (sut/materialize-execution-worktree! "/tmp/spec-dir" (str target))))
        (is (= ["git"
                "-C"
                "/tmp/source-repo"
                "worktree"
                "add"
                "--detach"
                (str (fs/absolutize target))]
               @calls)))
      (finally
        (delete-tree! tmp)))))
