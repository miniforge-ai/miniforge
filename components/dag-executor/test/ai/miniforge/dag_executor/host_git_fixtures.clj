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
(ns ai.miniforge.dag-executor.host-git-fixtures
  "Throwaway git repositories for the host-git guard tests.

   The leak these tests cover is a property of how real git shares a common
   dir between a checkout and its linked worktrees, so the fixtures build
   real repositories rather than stubbing git."
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

;; Constants and filesystem plumbing
(def ^{:stratum 0} host-origin-url
  "Obviously synthetic — `.invalid` is reserved and can never resolve, so a
   test that accidentally reaches the network fails loudly rather than
   touching a real remote."
  "https://example.invalid/host-origin.git")

(def ^{:stratum 0} redirected-origin-url
  "Stands in for the local bare mirror the 2026-08-06 bench redirected a
   live checkout's origin to."
  "https://example.invalid/redirected-mirror.git")

(def ^{:stratum 0} tracked-ref
  "The remote-tracking ref the 2026-08-06 incident moved backwards."
  "refs/remotes/origin/main")

(def ^{:stratum 0} seed-commit-count
  "Commits laid down by `init-host-repo!`. Three is the fewest that lets a
   test move a ref one commit forward and one commit backward from the same
   starting point."
  3)

(defn ^{:stratum 0} git!
  "Run git in `dir` and return the shell map. Fixture plumbing, so a
   non-zero exit surfaces through the assertion that consumes its effect
   rather than here."
  [dir & args]
  (apply shell/sh "git" "-C" (str dir) args))

(defn ^{:stratum 0} temp-dir!
  []
  (str (Files/createTempDirectory "miniforge-host-git" (make-array FileAttribute 0))))

(defn ^{:stratum 0} delete-tree!
  [path]
  (let [f (File. (str path))]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)] (delete-tree! child)))
      (.delete f))))

;------------------------------------------------------------------------------ Layer 1

;; Repository factories
(defn ^{:stratum 1} sha-at
  "Resolve `rev` in `dir` to a full SHA."
  [dir rev]
  (str/trim (:out (git! dir "rev-parse" rev))))

(defn ^{:stratum 1} init-upstream-and-host!
  "A bare upstream, a working clone that publishes to it, and a host
   checkout that tracks it — enough to drive a real `git fetch`.

   Returns `{:upstream :work :host}`. The host tracks `main` and
   `feature`; `force-push-feature!` then rewrites `feature` upstream, which
   is what a stacked miniforge DAG run produces every time a parent task's
   branch is re-pushed with `--force-with-lease`."
  [root]
  (let [upstream (str (File. (str root) "upstream.git"))
        work     (str (File. (str root) "work"))
        host     (str (File. (str root) "host"))]
    (git! root "init" "--quiet" "--bare" "upstream.git")
    (git! root "init" "--quiet" "-b" "main" "work")
    (git! work "config" "user.email" "guard-test@example.invalid")
    (git! work "config" "user.name" "Guard Test")
    (git! work "config" "commit.gpgsign" "false")
    (spit (str (File. work "seed.txt")) "seed\n")
    (git! work "add" "seed.txt")
    (git! work "commit" "--quiet" "-m" "seed")
    (git! work "remote" "add" "origin" upstream)
    (git! work "push" "--quiet" "origin" "main")
    (git! work "checkout" "--quiet" "-b" "feature")
    (git! work "commit" "--quiet" "--allow-empty" "-m" "feature work")
    (git! work "push" "--quiet" "origin" "feature")
    (git! root "clone" "--quiet" "upstream.git" "host")
    {:upstream upstream :work work :host host}))

(defn ^{:stratum 1} advance-feature!
  "Add a commit to `feature` upstream — the fast-forward case."
  [{:keys [work]}]
  (git! work "commit" "--quiet" "--allow-empty" "-m" "more feature work")
  (git! work "push" "--quiet" "origin" "feature"))

(defn ^{:stratum 1} force-push-feature!
  "Rewrite `feature` upstream so a following fetch is a forced update.

   Drops the tip and builds a different commit in its place, rather than
   amending — an amend of an empty commit needs `--allow-empty` again and
   fails quietly without it, leaving the branch unmoved and the test
   passing for no reason."
  [{:keys [work]}]
  (git! work "reset" "--quiet" "--hard" "HEAD~1")
  (git! work "commit" "--quiet" "--allow-empty" "-m" "feature work, rewritten")
  (git! work "push" "--quiet" "--force" "origin" "feature"))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} init-host-repo!
  "A stand-in for the checkout miniforge was launched from: a few commits,
   an origin whose URL must survive a task run, and a remote-tracking ref
   one commit behind the tip so a run has room to move it either way."
  [dir]
  (git! dir "init" "--quiet" "-b" "main")
  (git! dir "config" "user.email" "guard-test@example.invalid")
  (git! dir "config" "user.name" "Guard Test")
  (git! dir "config" "commit.gpgsign" "false")
  (dotimes [n seed-commit-count]
    (spit (str (File. (str dir) "seed.txt")) (str "seed " n "\n"))
    (git! dir "add" "seed.txt")
    (git! dir "commit" "--quiet" "--allow-empty" "-m" (str "seed " n)))
  (git! dir "remote" "add" "origin" host-origin-url)
  (git! dir "update-ref" tracked-ref (sha-at dir "HEAD~1"))
  dir)
