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
