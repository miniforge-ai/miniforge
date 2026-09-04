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
(ns ai.miniforge.workflow.isolation-test-support
  "Keeps pipeline-running tests off the developer's checkout and home.

   `runner/run-pipeline` defaults `:repo-path` to \".\" and, in :local
   mode, acquires a real git worktree from it: `git worktree add -b
   task-<8hex>` against whatever repository the test JVM was launched in.
   The worktree is removed on release; the branch is not. Persisted task
   bundles go under `~/.miniforge/checkpoints`, next to the run
   checkpoints `checkpoint-test-support` (#1890) already redirects.
   Observed 2026-09-03 on the trap bench: one `bb test` inside a linked
   worktree left 108 `task-*` branches on the enclosing repository within
   a minute, and the developer checkout was carrying 38k of them.

   `with-isolated-host` redirects every one of those sinks for the
   duration of a fixture:

   - repo-path \".\" or absent  -> a fresh throwaway host repository
   - worktree base path          -> <root>/worktrees
   - persisted-bundle archive    -> <root>/archives
   - default checkpoint root     -> a temp root, via checkpoint-test-support

   An explicit non-\".\" repo-path and an explicit `:checkpoint/root`
   execution option still win: a test that names its own target keeps
   it. Everything is deleted when the fixture unwinds.

   Registered as a `:once` fixture — one host repository per namespace is
   enough, and `with-redefs` must wrap the whole run of that namespace.
   Project-level twin: `ai.miniforge.workflow.isolation-support` under
   `projects/miniforge/test`, for the same reason `checkpoint-root-support`
   exists there — `bb test:integration` cannot load a brick's test dir.
   Keep the two bodies identical."
  (:require
   [ai.miniforge.workflow.checkpoint-test-support :as checkpoint]
   [ai.miniforge.workflow.runner-environment :as env]
   [clojure.java.shell :as shell])
  (:import
   [java.io File]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} host-branch
  "Branch the throwaway host repository is seeded on. Matches the
   `:branch` default `run-pipeline` acquires from."
  "main")

(defn ^{:stratum 0} temp-root!
  []
  (str (Files/createTempDirectory "miniforge-isolated-host"
                                  (make-array FileAttribute 0))))

(defn ^{:stratum 0} delete-tree!
  [path]
  (let [f (File. (str path))]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)] (delete-tree! child)))
      (.delete f))))

(defn ^{:stratum 0} git!
  "Run git in `dir` with a hermetic environment; throw on a non-zero exit.

   Hermetic: GIT_INDEX_FILE, GIT_DIR, GIT_WORK_TREE, GIT_COMMON_DIR and
   GIT_CONFIG_PARAMETERS are dropped. A git hook exports the first four to
   its children, and `git -c k=v` travels as the fifth; a `git init` that
   inherits them acts on the hook's repository, not the directory it was
   pointed at — under the pre-commit hook this fixture's `git init`
   re-initialised the launch repository as bare (`core.bare = true` in its
   shared config, 2026-09-03). Fixture setup that fails must fail here,
   not later as a confusing acquisition warning."
  [dir & args]
  (let [env (apply dissoc (into {} (System/getenv))
                   ["GIT_INDEX_FILE" "GIT_DIR" "GIT_WORK_TREE" "GIT_COMMON_DIR"
                    "GIT_CONFIG_PARAMETERS"])
        {:keys [exit err] :as result}
        (apply shell/sh "git" "-C" (str dir) (concat args [:env env]))]
    (when-not (zero? exit)
      (throw (ex-info "isolation fixture git command failed"
                      {:dir (str dir) :args (vec args) :exit exit :err err})))
    result))

(defn ^{:stratum 0} redirect-repo-path
  "`env-config` with a \".\" or absent `:repo-path` pointed at `host`.
   Any other explicit path is left alone."
  [env-config host]
  (let [repo-path (get env-config :repo-path)]
    (if (or (nil? repo-path) (= "." (str repo-path)))
      (assoc env-config :repo-path host)
      env-config)))

(defn ^{:stratum 0} worktree-config
  "Executor config that keeps worktrees and bundle archives under `root`."
  [root]
  {:base-path   (str root "/worktrees")
   :archive-dir (str root "/archives")})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} init-host-repo!
  "A stand-in for the checkout the test JVM was launched from: one commit
   on `host-branch`, signing off, no remote."
  [dir]
  (.mkdirs (File. (str dir)))
  (git! dir "init" "--quiet" "-b" host-branch)
  (git! dir "config" "user.email" "isolation-test@example.invalid")
  (git! dir "config" "user.name" "Isolation Test")
  (git! dir "config" "commit.gpgsign" "false")
  (spit (str (File. (str dir) "seed.txt")) "seed\n")
  (git! dir "add" "seed.txt")
  (git! dir "commit" "--quiet" "--no-verify" "-m" "seed")
  (str dir))

(defn ^{:stratum 1} isolated-registry-config
  "`registry-config-for-mode` with the worktree entry rooted under `root`.
   Only the :local shape carries a worktree entry; governed configs pass
   through untouched. `:local` ignores `:executor-config`, so this seam is
   the one place the worktree executor's paths can be set from a test."
  [original root]
  (fn [mode executor-config]
    (let [config (original mode executor-config)]
      (cond-> config
        (contains? config :worktree)
        (update :worktree merge (worktree-config root))))))

(defn ^{:stratum 1} isolated-acquire
  "`acquire-execution-environment!` with a \".\" repo-path redirected to
   `host`."
  [original host]
  (fn [workflow-id env-config]
    (original workflow-id (redirect-repo-path env-config host))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-isolated-host
  "clojure.test fixture: run `f` with every pipeline side effect that
   would otherwise reach the developer's checkout or `~/.miniforge`
   redirected into throwaway directories, then delete them. The
   checkpoint root goes through `call-with-temp-checkpoint-root` (#1890);
   the host repository, worktree base, and bundle archive are this
   namespace's own."
  [f]
  (checkpoint/call-with-temp-checkpoint-root
   (fn [_checkpoint-root]
     (let [root (temp-root!)
           host (init-host-repo! (str root "/host"))]
       (try
         (with-redefs [env/registry-config-for-mode
                       (isolated-registry-config env/registry-config-for-mode root)
                       env/acquire-execution-environment!
                       (isolated-acquire env/acquire-execution-environment! host)]
           (f))
         (finally
           (delete-tree! root)))))))
