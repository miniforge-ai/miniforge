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
(ns ai.miniforge.dag-executor.workspace
  "Git-based workspace persistence shared by Docker and K8s executors.

   Provides persist and restore functions that take an exec-fn (command
   executor for the environment) and delegate the git operations. Each
   executor implementation constructs its own exec-fn and passes it here.

   OSS tier uses git push/fetch. Fleet tier will add object store
   (S3/GCS/MinIO) as an alternative persistence backend."
  (:require
   [ai.miniforge.dag-executor.result :as result]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} safe-branch?
  "Branch names that start with '-' are parsed by git as options even in arg-vector
   mode (no shell involved). Reject them before invoking any git subcommand.
   Non-string values are also invalid — argv elements must be Strings."
  [branch]
  (and (string? branch) (not (str/blank? branch)) (not (str/starts-with? branch "-"))))

(defn- ^{:stratum 0} failed-step
  "The step's stderr when its exit code is non-zero, else nil. An exec
   result without an exit code is trusted (older stubs and shells)."
  [r fallback]
  (when (and r (not (zero? (get-in r [:data :exit-code] 0))))
    (get-in r [:data :stderr] fallback)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} git-persist!
  "Persist workspace via git commit + push.

   Arguments:
   - exec-fn: (fn [cmd] -> result-map) — executes a command in the environment;
              cmd may be a string (passed to sh -c) or a vector (exec'd directly,
              no shell — preferred for user-supplied values to avoid injection)
   - opts: {:branch string, :message string}

   Returns result monad with {:persisted? bool :commit-sha string :branch string}.
   Every git step's exit code is checked: a failed commit or push that
   still reported :persisted? true was a silent loss of work."
  [exec-fn {:keys [branch message] :or {branch "task/unknown" message "phase checkpoint"}}]
  (if (not (safe-branch? branch))
    (result/err :invalid-branch (str "Branch must be a non-empty string not starting with '-': " (pr-str branch)))
    (try
      (let [_ (exec-fn "git add -A")
            status-r (exec-fn "git status --porcelain")
            has-changes? (seq (str/trim (get-in status-r [:data :stdout] "")))]
        (if-not has-changes?
          (result/ok {:persisted? false :commit-sha nil :no-changes? true :branch branch})
          ;; Unsigned: a scratch-worktree commit must not depend on the
          ;; operator's signing agent (see worktree tier commit-staged!).
          (let [commit-r (exec-fn ["git" "-c" "commit.gpgsign=false" "commit" "-m" (str message)])
                push-r (when-not (failed-step commit-r "")
                         (exec-fn ["git" "push" "origin" (str "HEAD:" branch) "--force"]))
                sha-r (when (and push-r (not (failed-step push-r "")))
                        (exec-fn "git rev-parse HEAD"))]
            (cond
              (failed-step commit-r "") (result/err :persist-commit-failed (failed-step commit-r "git commit failed"))
              (failed-step push-r "")   (result/err :persist-push-failed (failed-step push-r "git push failed"))
              (failed-step sha-r "")    (result/err :persist-sha-failed (failed-step sha-r "git rev-parse failed"))
              :else (result/ok {:persisted? true
                                :commit-sha (str/trim (get-in sha-r [:data :stdout] ""))
                                :branch branch})))))
      (catch Exception e
        (result/err :persist-failed (.getMessage e))))))

(defn ^{:stratum 1} git-restore!
  "Restore workspace via git fetch + checkout.

   Arguments:
   - exec-fn: (fn [cmd] -> result-map) — executes a command in the environment;
              cmd may be a string (passed to sh -c) or a vector (exec'd directly,
              no shell — preferred for user-supplied values to avoid injection)
   - opts: {:branch string}

   Returns result monad with {:restored? bool :commit-sha string :branch string}"
  [exec-fn {:keys [branch] :or {branch "task/unknown"}}]
  (if (not (safe-branch? branch))
    (result/err :invalid-branch (str "Branch must be a non-empty string not starting with '-': " (pr-str branch)))
    (try
      (let [fetch-r (exec-fn ["git" "fetch" "origin" branch])]
        (if-let [e (failed-step fetch-r "git fetch failed")]
          (result/err :restore-fetch-failed e)
          (let [co-r (exec-fn ["git" "checkout" branch])]
            (if-let [e (failed-step co-r "git checkout failed")]
              (result/err :restore-checkout-failed e)
              (let [sha-r (exec-fn "git rev-parse HEAD")]
                (if-let [e (failed-step sha-r "git rev-parse failed")]
                  (result/err :restore-sha-failed e)
                  (result/ok {:restored? true
                              :commit-sha (str/trim (get-in sha-r [:data :stdout] ""))
                              :branch branch})))))))
      (catch Exception e
        (result/err :restore-failed (.getMessage e))))))
