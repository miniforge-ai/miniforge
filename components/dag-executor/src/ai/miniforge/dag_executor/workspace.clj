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

(defn git-persist!
  "Persist workspace via git commit + push.

   Arguments:
   - exec-fn: (fn [cmd] -> result-map) — executes a shell command in the environment
   - opts: {:branch string, :message string}

   Returns result monad with {:persisted? bool :commit-sha string :branch string}"
  [exec-fn {:keys [branch message] :or {branch "task/unknown" message "phase checkpoint"}}]
  (try
    (let [_ (exec-fn "git add -A")
          status-r (exec-fn "git status --porcelain")
          has-changes? (seq (str/trim (get-in status-r [:data :stdout] "")))]
      (if has-changes?
        (let [_ (exec-fn (str "git commit -m '" message "'"))
              _ (exec-fn (str "git push origin HEAD:" branch " --force"))
              sha-r (exec-fn "git rev-parse HEAD")
              sha   (str/trim (get-in sha-r [:data :stdout] ""))]
          (result/ok {:persisted? true :commit-sha sha :branch branch}))
        (result/ok {:persisted? false :commit-sha nil :no-changes? true})))
    (catch Exception e
      (result/err :persist-failed (.getMessage e)))))

(defn git-restore!
  "Restore workspace via git fetch + checkout.

   Arguments:
   - exec-fn: (fn [cmd] -> result-map) — executes a shell command in the environment
   - opts: {:branch string}

   Returns result monad with {:restored? bool :commit-sha string :branch string}"
  [exec-fn {:keys [branch] :or {branch "task/unknown"}}]
  (try
    (let [_ (exec-fn (str "git fetch origin " branch))
          _ (exec-fn (str "git checkout " branch))
          sha-r (exec-fn "git rev-parse HEAD")
          sha   (str/trim (get-in sha-r [:data :stdout] ""))]
      (result/ok {:restored? true :commit-sha sha :branch branch}))
    (catch Exception e
      (result/err :restore-failed (.getMessage e)))))
