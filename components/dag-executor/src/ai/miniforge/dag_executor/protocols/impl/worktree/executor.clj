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
(ns ai.miniforge.dag-executor.protocols.impl.worktree.executor
  "Worktree executor implementation (fallback).

   Provides task isolation via git worktrees. This is the fallback executor
   when no container runtime is available. Isolation is limited to filesystem
   separation - processes run on the host."
  (:require
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.runtime.process :as runtime-process]
   [ai.miniforge.dag-executor.protocols.impl.worktree :as worktree]
   [ai.miniforge.dag-executor.protocols.impl.worktree.archive :as archive]
   [ai.miniforge.dag-executor.protocols.impl.worktree.drain :as drain]
   [ai.miniforge.dag-executor.protocols.impl.worktree.files :as files]
   [ai.miniforge.dag-executor.protocols.impl.worktree.paths :as paths]
   [ai.miniforge.dag-executor.protocols.impl.worktree.provision :as provision]
   [ai.miniforge.dag-executor.protocols.impl.worktree.registry :as registry]
   [ai.miniforge.dag-executor.result :as result])
  (:import
   [java.io File]
   [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-max-concurrent 4)

(defn ^{:stratum 0} execute-command
  "Execute a command in a directory using ProcessBuilder.

   Accepts either a string (passed to sh -c, suitable for shell builtins and
   pipelines) or a vector of strings (exec'd directly without a shell —
   preferred for user-supplied values to avoid shell injection).

   stdout and stderr are drained on separate threads while the child runs,
   so a child that fills the OS pipe buffer (64 KB) on one stream before
   closing the other cannot deadlock the parent.

   When `timeout-ms` is a positive number the wait is bounded: on expiry
   the whole process tree is destroyed and the result carries exit
   `timeout-exit-code`, the partial output, and `:timed-out? true`. A nil
   or non-positive `timeout-ms` waits without a deadline."
  ([workdir command env-map]
   (execute-command workdir command env-map nil))
  ([workdir command env-map timeout-ms]
   (let [cmd-args (if (string? command)
                    ["sh" "-c" command]
                    (vec command))
         start-time (System/currentTimeMillis)]
     (try
       (let [pb (ProcessBuilder. ^java.util.List cmd-args)
             _ (.directory pb (File. ^String workdir))
             _ (when (seq env-map)
                 (let [pb-env (.environment pb)]
                   (doseq [[k v] env-map]
                     (.put pb-env (name k) (str v)))))
             process      (.start pb)
             stdout-drain (runtime-process/drain-stream (.getInputStream process))
             stderr-drain (runtime-process/drain-stream (.getErrorStream process))]
         (try
           (let [timed?     (and (number? timeout-ms) (pos? timeout-ms))
                 completed? (if timed?
                              (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
                              (do (.waitFor process) true))]
             (if completed?
               (do
                 ;; Wait for end-of-stream so the last pipe-buffered bytes land.
                 ;; Bounded when a deadline was given: an orphan grandchild
                 ;; holding the pipe must not outlive the caller's deadline.
                 (if timed?
                   (drain/settle-drains! stdout-drain stderr-drain)
                   (do @(:future stdout-drain)
                       @(:future stderr-drain)))
                 (result/ok {:exit-code   (.exitValue process)
                             :stdout      (runtime-process/drained-text stdout-drain)
                             :stderr      (runtime-process/drained-text stderr-drain)
                             :duration-ms (- (System/currentTimeMillis) start-time)}))
               (do
                 (drain/kill-and-collect! process stdout-drain stderr-drain)
                 (drain/timed-out-result start-time stdout-drain stderr-drain))))
           (catch InterruptedException e
             ;; The caller's thread was interrupted mid-wait: do not leak the
             ;; child, and keep the interrupt visible to whoever is unwinding.
             (drain/kill-and-collect! process stdout-drain stderr-drain)
             (.interrupt (Thread/currentThread))
             (result/err :exec-interrupted (.getMessage e)))))
       (catch Exception e
         (result/err :exec-failed (.getMessage e)))))))

;------------------------------------------------------------------------------ Layer 1

(defrecord ^{:stratum 1} WorktreeExecutor [config base-path max-concurrent]
  proto/TaskExecutor

  (executor-type [_this] :worktree)

  (available? [_this]
    (try
      (result/ok (worktree/git-version))
      (catch Exception e
        (result/ok {:available? false :reason (.getMessage e)}))))

  (acquire-environment! [_this task-id env-config]
    (let [worktree-name (str "task-" (subs (str task-id) 0 8))
          repo-path     (get env-config :repo-path ".")
          branch        (get env-config :branch "main")
          workflow-id   (get env-config :workflow-id)
          create-result (provision/create-worktree base-path repo-path worktree-name branch)]
      (if (result/ok? create-result)
        (let [data          (:data create-result)
              worktree-path (:worktree-path data)]
          ;; Lifecycle hook: register the scratch-ref for this workflow so
          ;; callers can call notify-file-written! and find the right path.
          (when workflow-id
            (registry/register-worktree-entry! workflow-id worktree-path))
          (result/ok (proto/create-environment-record
                      worktree-name :worktree task-id worktree-path
                      (assoc env-config
                             :metadata {:worktree-path worktree-path
                                        :repo-path     repo-path
                                        :base-sha      (:base-sha data)
                                        ;; Stored so persist-workspace! can bundle
                                        ;; the right base..HEAD range without
                                        ;; guessing the parent branch.
                                        :base-branch   branch}))))
        create-result)))

  (execute! [_this environment-id command opts]
    (let [worktree-path (str base-path "/" environment-id)
          workdir (get opts :workdir worktree-path)
          env-map (merge {} (:env opts))]
      (execute-command workdir command env-map (get opts :timeout-ms))))

  (copy-to! [_this environment-id local-path remote-path]
    (let [worktree-path (str base-path "/" environment-id)]
      (files/copy-file-to worktree-path local-path remote-path)))

  (copy-from! [_this environment-id remote-path local-path]
    (let [worktree-path (str base-path "/" environment-id)]
      (files/copy-file-from worktree-path remote-path local-path)))

  (release-environment! [_this environment-id]
    (let [worktree-path (str base-path "/" environment-id)]
      ;; Lifecycle hook: mark the registry entry as :released while preserving
      ;; the scratch ref. The scratch ref survives until gc-scratch-refs! runs.
      (when-let [wf-id (registry/find-workflow-id-by-worktree worktree-path)]
        (registry/release-worktree-entry! wf-id))
      (try
        (worktree/remove-worktree worktree-path)
        (catch Exception e
          (result/err :release-failed (.getMessage e))))))

  (environment-status [_this environment-id]
    (let [worktree-path (str base-path "/" environment-id)]
      (if (.exists (File. ^String worktree-path))
        (result/ok {:status :running})
        (result/ok {:status :stopped}))))

  (persist-workspace! [_this environment-id opts]
    ;; Local-tier persistence parity with :governed (per N11 §7.4 fidelity goal):
    ;; bundle the task branch into a durable archive so work survives
    ;; release-environment!. Without this, the host worktree had no record of
    ;; per-task work — successful agent output was destroyed when the scratch
    ;; worktree was torn down.
    ;;
    ;; `:base-ref` only falls back across :base-branch -> :base-ref ->
    ;; default-base-ref. The task branch (`:branch` opt) is intentionally
    ;; NOT a fallback: using it would make `commits-ahead` compute
    ;; `task..HEAD` (always 0) and silently skip bundling.
    (let [worktree-path (str base-path "/" environment-id)
          configured-archive-dir (or (get opts :archive-dir)
                                     (get-in config [:archive-dir])
                                     (paths/default-archive-dir))
          ;; Workflow id (when present) namespaces the archive path so
          ;; concurrent runs do not collide on the same task-id stem.
          archive-dir (if-let [wf (get opts :workflow-id)]
                        (str configured-archive-dir "/" wf)
                        configured-archive-dir)
          base-ref    (or (get opts :base-branch)
                          (get opts :base-ref)
                          archive/default-base-ref)
          task-id     (or (get opts :task-id) environment-id)
          message     (or (get opts :message) archive/default-commit-message)]
      (archive/archive-bundle! worktree-path
                       {:archive-dir archive-dir
                        :task-id     task-id
                        :base-ref    base-ref
                        :message     message})))

  (restore-workspace! [_this _environment-id opts]
    ;; Pull a previously-persisted bundle back into the host repo object DB.
    ;; Caller supplies :host-repo-path (any worktree of the host repo works)
    ;; and :bundle-path. The branch shows up as a ref in the host repo and
    ;; can be checked out wherever the orchestrator wants.
    (let [host-repo (or (get opts :host-repo-path)
                        (get opts :workdir)
                        ".")]
      (archive/restore-from-bundle! host-repo opts))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create-worktree-executor
  "Create a worktree-based executor (fallback).

   Config:
   - :base-path              - Explicit base directory for worktrees. Takes
                               priority over all other path resolution.
   - :workflow/worktree-root - User-config key. The shipped
                               `default-user-config.edn` sets this to
                               `\"~/.miniforge/worktrees\"`. Leading `~/`
                               is expanded to the user home directory.
                               Falls back to the hardcoded default when
                               absent or blank.
   - :max-concurrent         - Max concurrent worktrees (default: 4)

   The previous `/tmp/miniforge-worktrees` default was changed after the
   2026-04-17 gates-workflow lost 8+ minutes of working code to a macOS
   tmpfs cleanup. Ephemeral `/tmp`-rooted runs (CI, sandbox tests) opt in
   by setting `:workflow/worktree-root \"/tmp/miniforge-worktrees\"` in user
   config, or `:base-path` directly in the executor config."
  [config]
  (map->WorktreeExecutor
   {:config         config
    :base-path      (or (get config :base-path)
                        (paths/default-base-path config))
    :max-concurrent (get config :max-concurrent default-max-concurrent)}))
