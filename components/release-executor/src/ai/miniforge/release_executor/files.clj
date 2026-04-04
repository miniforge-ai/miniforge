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

(ns ai.miniforge.release-executor.files
  "File operations for the release executor.
   Handles writing, deleting, and staging code artifact files."
  (:require
   [ai.miniforge.release-executor.git :as git]
   [ai.miniforge.release-executor.result :as result]
   [ai.miniforge.logging.interface :as log]
   [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0
;; File operation helpers

(defn ensure-parent-dir!
  "Create parent directories for a file path if they don't exist."
  [file-path]
  (let [parent (fs/parent file-path)]
    (when parent
      (fs/create-dirs parent))
    parent))

(defn write-file!
  "Write content to a file, creating parent directories as needed."
  [file-path content]
  (ensure-parent-dir! file-path)
  (spit (str file-path) content)
  file-path)

(defn delete-file!
  "Delete a file if it exists. Returns true if deleted, false if file didn't exist."
  [file-path]
  (if (fs/exists? file-path)
    (do
      (fs/delete file-path)
      true)
    false))

;------------------------------------------------------------------------------ Layer 1
;; File action processing

(defmulti process-file-action
  "Process a file action based on its :action keyword."
  (fn [_worktree-path file-spec _logger] (:action file-spec)))

(defmethod process-file-action :create
  [worktree-path {:keys [path content]} logger]
  (try
    (let [full-path (fs/path worktree-path path)]
      (write-file! full-path content)
      (when logger
        (log/debug logger :release-executor :file-created {:data {:path path}}))
      {:success? true :action :create :path path})
    (catch Exception e
      (when logger
        (log/error logger :release-executor :file-create-failed
                  {:message (.getMessage e) :data {:path path}}))
      {:success? false :action :create :path path :error (.getMessage e)})))

(defmethod process-file-action :modify
  [worktree-path {:keys [path content]} logger]
  (try
    (let [full-path (fs/path worktree-path path)]
      (write-file! full-path content)
      (when logger
        (log/debug logger :release-executor :file-modified {:data {:path path}}))
      {:success? true :action :modify :path path})
    (catch Exception e
      (when logger
        (log/error logger :release-executor :file-modify-failed
                  {:message (.getMessage e) :data {:path path}}))
      {:success? false :action :modify :path path :error (.getMessage e)})))

(defmethod process-file-action :delete
  [worktree-path {:keys [path]} logger]
  (try
    (let [full-path (fs/path worktree-path path)
          deleted? (delete-file! full-path)]
      (when logger
        (log/debug logger :release-executor :file-deleted {:data {:path path :existed? deleted?}}))
      {:success? true :action :delete :path path :deleted? deleted?})
    (catch Exception e
      (when logger
        (log/error logger :release-executor :file-delete-failed
                  {:message (.getMessage e) :data {:path path}}))
      {:success? false :action :delete :path path :error (.getMessage e)})))

(defmethod process-file-action :default
  [_worktree-path {:keys [path action]} logger]
  (when logger
    (log/warn logger :release-executor :unknown-action {:data {:path path :action action}}))
  {:success? false :action action :path path :error (str "Unknown action: " action)})

;------------------------------------------------------------------------------ Layer 2
;; Batch file operations

(defn write-and-stage-files!
  "Write files to worktree and stage them. Returns result map."
  [worktree-path code-artifacts logger]
  (let [results (atom {:created 0 :modified 0 :deleted 0 :errors []})
        all-files (mapcat :code/files code-artifacts)]

    (doseq [file-spec all-files]
      (let [result (process-file-action worktree-path file-spec logger)]
        (if (result/succeeded? result)
          (case (:action result)
            :create (swap! results update :created inc)
            :modify (swap! results update :modified inc)
            :delete (swap! results update :deleted inc)
            nil)
          (swap! results update :errors conj
                 {:type :file-operation-failed
                  :message (:error result)
                  :file (:path result)
                  :action (:action result)}))))

    (let [{:keys [created modified deleted errors]} @results
          total-operations (+ created modified deleted)]
      (if (seq errors)
        {:success? false
         :errors errors
         :metrics {:files-written created :files-modified modified :files-deleted deleted}}
        (let [written-paths (map :path all-files)
              stage-result (git/stage-files! worktree-path written-paths)]
          (if (result/succeeded? stage-result)
            {:success? true
             :metrics {:files-written created :files-modified modified :files-deleted deleted
                       :total-operations total-operations}}
            {:success? false
             :errors [{:type :git-stage-failed :message (:error stage-result)}]
             :metrics {:files-written created :files-modified modified :files-deleted deleted}}))))))
