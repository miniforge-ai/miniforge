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
(ns ai.miniforge.dag-executor.protocols.impl.worktree.paths
  "Where task worktrees and archives live, and the advisory for the
   legacy `/tmp` worktree root."
  (:require
   [ai.miniforge.config.interface :as config]
   [clojure.string :as str])
  (:import
   [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:dynamic *warn-fn*
  "Emit a warning message to stderr.
   Rebind via `binding` in tests to capture warnings without stderr noise."
  (fn [msg] (binding [*out* *err*] (println msg))))

(defn- ^{:stratum 0} expand-home
  "Expand a leading `~/` in a path string to the user home directory.
   Pass-through for absolute paths and bare paths that do not start with `~/`."
  [path]
  (let [home (System/getProperty "user.home")]
    (cond
      (str/starts-with? path "~/") (str home (subs path 1))
      (= "~" path)                 home
      :else                        path)))

(defn ^{:stratum 0} default-archive-dir
  "Default location for persisted task work archives:
   `<miniforge-home>/checkpoints`, not /tmp, so checkpoints survive reboots
   and can be referenced from evidence bundles long after the run ends."
  []
  (str (config/miniforge-home) "/checkpoints"))

(def ^{:stratum 0} ^:private legacy-tmp-worktrees-path
  "Legacy default worktree base directory that lived under /tmp.
   Created before the 2026-04-17 gates-workflow data-loss incident forced the
   move to `~/.miniforge/worktrees`. Checked at creation time to prompt
   migration without blocking active workflows."
  "/tmp/miniforge-worktrees")

;; Non-private so tests can `(reset! worktree-paths/legacy-tmp-warned? false)` before
;; exercising the warning path. `compare-and-set!` in check-legacy-tmp-worktrees!
;; ensures the advisory fires at most once per JVM session even under concurrent
;; load — without this, all four default worktree slots could race to emit
;; identical WARNING lines before any of them acquires worktree-lock.
(defonce ^{:stratum 0} legacy-tmp-warned?
  (atom false))

(defn ^{:stratum 0} ensure-directory
  "Ensure a directory exists."
  [path]
  (.mkdirs (File. ^String path)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} default-base-path
  "Default location for git worktrees acquired by the worktree executor.

   Arity 0: returns `<miniforge-home>/worktrees` (MINIFORGE_HOME, else
   `~/.miniforge`).

   Arity 1 (config map): reads `:workflow/worktree-root` from the supplied
   map, expands any leading `~/`, and falls back to the no-arg default when
   the key is absent or blank. This lets `create-worktree-executor` respect
   the user `default-user-config.edn` setting without requiring a disk read
   at namespace load time.

   Lives under `~/.miniforge/worktrees/` rather than `/tmp` so the worktree
   (and any uncommitted implementer file writes living in it) survives reboots
   and macOS-tmpfs cleanups. The 2026-04-17 gates workflow lost 8+ minutes of
   working code when its `/tmp` worktree was GC'd on process-tree restart;
   persistence outside `/tmp` is the foundation for the broader work in
   `work/worktree-persistence-scratch-branch.spec.edn`.

   Callers that need an ephemeral location (CI runs, sandbox tests) can opt in
   to `/tmp` by setting `:workflow/worktree-root \"/tmp/miniforge-worktrees\"`
   in their user config, or by passing `:base-path` directly to
   `create-worktree-executor`."
  ([]
   (str (config/miniforge-home) "/worktrees"))
  ([config]
   (let [configured (get config :workflow/worktree-root)]
     (if (and configured (not (str/blank? (str configured))))
       (expand-home configured)
       (default-base-path)))))

(defn ^{:stratum 1} legacy-tmp-worktrees-exist?
  "Returns true if the legacy /tmp/miniforge-worktrees directory exists and
   contains at least one entry (indicating active or stale worktrees).

   Public so tests can rebind via `with-redefs` without touching the filesystem."
  []
  (let [dir (File. ^String legacy-tmp-worktrees-path)]
    (and (.exists dir)
         (.isDirectory dir)
         (let [files (.listFiles dir)]
           (and (some? files) (pos? (alength files)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check-legacy-tmp-worktrees!
  "Warn once per JVM session when the legacy /tmp/miniforge-worktrees directory
   has entries.

   Uses `compare-and-set!` on `legacy-tmp-warned?` so exactly one thread emits
   the advisory, even when multiple worktree-create calls race at startup.

   Does NOT error — graceful coexistence per the worktree-persistence spec
   constraint. Existing /tmp worktrees remain usable; the warning tells
   operators how to migrate to the persistent default path.

   Rebind `*warn-fn*` via `binding` in tests to capture the output.
   Reset `legacy-tmp-warned?` to false before tests that assert the warning fires."
  []
  (when (and (legacy-tmp-worktrees-exist?)
             (compare-and-set! legacy-tmp-warned? false true))
    (*warn-fn*
     (str
      "[miniforge] WARNING: legacy worktrees detected at "
      legacy-tmp-worktrees-path "\n"
      "  These worktrees live under /tmp and will not survive a reboot.\n"
      "  To migrate to the persistent default:\n"
      "    1. Add `:workflow/worktree-root \"~/.miniforge/worktrees\"` to\n"
      "       ~/.miniforge/config.edn (this is already the shipped default).\n"
      "    2. Let in-flight tasks complete, then remove the stale /tmp directory:\n"
      "       rm -rf " legacy-tmp-worktrees-path "\n"
      "  New worktrees are now created under: " (default-base-path)))))
