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
   [clojure.java.shell :as shell]
   [clojure.string :as str])
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
  "Delete `path` and everything under it; a missing path is a no-op.

   A symbolic link is deleted as a link and never entered.
   `File.isDirectory` follows links, so recursing on its answer alone
   deletes the files in the link's target, outside the tree.
   `File.exists` follows them too and is not asked: a link whose target
   is gone would be left behind and keep its parent from being deleted."
  [path]
  (let [f (File. (str path))]
    (when (and (not (Files/isSymbolicLink (.toPath f)))
               (.isDirectory f))
      (doseq [child (.listFiles f)] (delete-tree! child)))
    (.delete f)))

(def ^{:stratum 0} host-config
  "Local config of the throwaway host repository: an identity for the
   commits the code under test makes in it, and signing off."
  [["user.email" "isolation-test@example.invalid"]
   ["user.name" "Isolation Test"]
   ["commit.gpgsign" "false"]])

(defn ^{:stratum 0} launch-env
  "The environment this JVM was launched with, as a map."
  []
  (into {} (System/getenv)))

(defn ^{:stratum 0} config-args
  "git arguments that set `k` to `v` in the config file of the repository
   at `dir`, named by absolute path. A write that names its file cannot
   land in another repository whatever the environment says, and fails
   when `dir` holds no repository instead of finding an enclosing one."
  [dir [k v]]
  ["config" "--file"
   (.getAbsolutePath (File. (File. (str dir) ".git") "config"))
   k v])

(defn ^{:stratum 0} git!
  "Run git in `dir` with `process-env` minus every `GIT_*` variable;
   throw on a non-zero exit.

   In a linked worktree git exports `GIT_DIR` to its hooks and obeys it
   over `-C`: a `git init` or `git config` that inherits a hook's
   environment acts on the hook's repository, whose config all its
   worktrees share. The whole prefix goes, not a list of names:
   `GIT_COMMON_DIR` and `GIT_CONFIG` each redirect a config write too.
   Fixture setup that fails must fail here, not later as a confusing
   acquisition warning."
  [process-env dir & args]
  (let [hermetic (into {}
                       (remove (fn [[k _]] (str/starts-with? k "GIT_")))
                       process-env)
        {:keys [exit err] :as result}
        (apply shell/sh "git" "-C" (str dir) (concat args [:env hermetic]))]
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
   on `host-branch`, `host-config` applied, no remote. `process-env`
   defaults to the JVM's own; `git!` scrubs it either way.

   `--template=` makes `git init` copy no template. The scrub drops
   `GIT_TEMPLATE_DIR`, but `init.templateDir` in the developer's global
   config can still name one, and git copies a linked `hooks` entry as
   a link — into a tree this fixture later deletes."
  ([dir]
   (init-host-repo! dir (launch-env)))
  ([dir process-env]
   (.mkdirs (File. (str dir)))
   (git! process-env dir "init" "--quiet" "--template=" "-b" host-branch)
   (doseq [entry host-config]
     (apply git! process-env dir (config-args dir entry)))
   (spit (str (File. (str dir) "seed.txt")) "seed\n")
   (git! process-env dir "add" "seed.txt")
   (git! process-env dir "commit" "--quiet" "--no-verify" "-m" "seed")
   (str dir)))

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
