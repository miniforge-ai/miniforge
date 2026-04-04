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

(ns ai.miniforge.workflow.release
  "Release phase executor for writing code artifacts to git worktree.
   
   The release phase takes code artifacts from the implement phase and writes them
   to a git worktree, staging the changes for PR creation. PR creation itself is
   handled externally by human or CLI tools.
   
   Layer 0: File operation helpers
   Layer 1: Git operations
   Layer 2: File action processing
   Layer 3: Code artifact extraction
   Layer 4: ReleasePhaseExecutor implementation"
  (:require
   [ai.miniforge.workflow.interface.protocols.phase-executor :as p]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.logging.interface :as log]
   [babashka.fs :as fs]
   [babashka.process :as process]))

;------------------------------------------------------------------------------ Layer 0
;; Result predicates

(defn succeeded?
  "Check if a result map indicates success."
  [result]
  (boolean (:success? result)))

;; File operation helpers

(defn ensure-parent-dir!
  "Create parent directories for a file path if they don't exist.
   Returns the parent directory path."
  [file-path]
  (let [parent (fs/parent file-path)]
    (when parent
      (fs/create-dirs parent))
    parent))

(defn write-file!
  "Write content to a file, creating parent directories as needed.
   Returns the file path on success."
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
;; Git operations

(defn stage-files!
  "Stage files in git worktree using git add.
   
   Arguments:
   - worktree-path - Path to git worktree root
   - file-paths    - Collection of file paths relative to worktree (or :all for all changes)
   
   Returns {:success? boolean :output string :error string}"
  [worktree-path file-paths]
  (try
    (let [git-args (if (= file-paths :all)
                     ["git" "add" "."]
                     (into ["git" "add"] (map str file-paths)))
          result (apply process/shell
                        {:dir (str worktree-path)
                         :out :string
                         :err :string
                         :continue true}
                        git-args)]
      {:success? (zero? (:exit result))
       :output (:out result "")
       :error (:err result "")})
    (catch Exception e
      {:success? false
       :output ""
       :error (.getMessage e)})))

;------------------------------------------------------------------------------ Layer 2
;; File action processing

(defmulti process-file-action
  "Process a file action based on its :action keyword.
   
   Dispatches on the :action field of the file map.
   Returns a result map with :success?, :action, :path, and optional :error."
  (fn [_worktree-path file-spec _logger] (:action file-spec)))

(defmethod process-file-action :create
  [worktree-path {:keys [path content]} logger]
  (try
    (let [full-path (fs/path worktree-path path)]
      (write-file! full-path content)
      (when logger
        (log/debug logger :workflow :release/file-created
                  {:data {:path path}}))
      {:success? true
       :action :create
       :path path})
    (catch Exception e
      (when logger
        (log/error logger :workflow :release/file-create-failed
                  {:message (.getMessage e)
                   :data {:path path}}))
      {:success? false
       :action :create
       :path path
       :error (.getMessage e)})))

(defmethod process-file-action :modify
  [worktree-path {:keys [path content]} logger]
  (try
    (let [full-path (fs/path worktree-path path)]
      (write-file! full-path content)
      (when logger
        (log/debug logger :workflow :release/file-modified
                  {:data {:path path}}))
      {:success? true
       :action :modify
       :path path})
    (catch Exception e
      (when logger
        (log/error logger :workflow :release/file-modify-failed
                  {:message (.getMessage e)
                   :data {:path path}}))
      {:success? false
       :action :modify
       :path path
       :error (.getMessage e)})))

(defmethod process-file-action :delete
  [worktree-path {:keys [path]} logger]
  (try
    (let [full-path (fs/path worktree-path path)
          deleted? (delete-file! full-path)]
      (when logger
        (log/debug logger :workflow :release/file-deleted
                  {:data {:path path :existed? deleted?}}))
      {:success? true
       :action :delete
       :path path
       :deleted? deleted?})
    (catch Exception e
      (when logger
        (log/error logger :workflow :release/file-delete-failed
                  {:message (.getMessage e)
                   :data {:path path}}))
      {:success? false
       :action :delete
       :path path
       :error (.getMessage e)})))

(defmethod process-file-action :default
  [_worktree-path {:keys [path action]} logger]
  (when logger
    (log/warn logger :workflow :release/unknown-action
             {:data {:path path :action action}}))
  {:success? false
   :action action
   :path path
   :error (str "Unknown action: " action)})

;------------------------------------------------------------------------------ Layer 3
;; Code artifact extraction

(defn extract-code-artifacts
  "Extract code artifacts from workflow artifacts.
   Handles both old and new artifact formats.
   
   Returns a sequence of code artifact contents."
  [workflow-artifacts]
  (->> workflow-artifacts
       (filter #(or (= :code (:type %))
                    (= :code (:artifact/type %))))
       (map (fn [artifact]
              (or
               ;; New standard format
               (:artifact/content artifact)
               ;; Old format
               (:content artifact))))))

;------------------------------------------------------------------------------ Layer 4
;; ReleasePhaseExecutor implementation

(defrecord ReleasePhaseExecutor []
  p/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (let [logger (:logger context)
          worktree-path (:worktree-path context)
          artifact-store (:artifact-store context)]
      
      (when logger
        (log/info logger :workflow :release/phase-started
                 {:data {:worktree-path worktree-path}}))
      
      ;; Validate worktree-path is present
      (if-not worktree-path
        (do
          (when logger
            (log/error logger :workflow :release/missing-worktree-path
                      {:message "Context must include :worktree-path"}))
          {:success? false
           :artifacts []
           :errors [{:type :missing-worktree-path
                     :message "Context must include :worktree-path for release phase"}]
           :metrics {}})
        
        ;; Extract code artifacts
        (let [code-artifacts (extract-code-artifacts (:workflow/artifacts workflow-state))]
          
          (if (empty? code-artifacts)
            (do
              (when logger
                (log/warn logger :workflow :release/no-code-artifacts
                         {:message "No code artifacts found to release"}))
              {:success? false
               :artifacts []
               :errors [{:type :no-code-artifacts
                         :message "No code artifacts found in workflow state"}]
               :metrics {}})
            
            ;; Process all files from all code artifacts
            (let [results (atom {:created 0 :modified 0 :deleted 0 :errors []})
                  all-files (mapcat :code/files code-artifacts)]
              
              ;; Process each file
              (doseq [file-spec all-files]
                (let [result (process-file-action worktree-path file-spec logger)]
                  (if (succeeded? result)
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
                
                ;; Stage files with git if no errors
                (if (empty? errors)
                  (let [stage-result (stage-files! worktree-path :all)]
                    (if (succeeded? stage-result)
                      (do
                        (when logger
                          (log/info logger :workflow :release/git-staged
                                   {:data {:files-staged total-operations}}))
                        
                        ;; Build release artifact
                        (let [release-content {:files-written created
                                               :files-modified modified
                                               :files-deleted deleted
                                               :total-operations total-operations
                                               :git-staged? true
                                               :worktree-path (str worktree-path)}
                              release-artifact (artifact/build-artifact
                                               {:id (random-uuid)
                                                :type :release
                                                :version "1.0.0"
                                                :content release-content
                                                :metadata {:phase :release
                                                          :code-artifacts-count (count code-artifacts)}})]
                          
                          ;; Persist artifact if store available
                          (when artifact-store
                            (try
                              (artifact/save! artifact-store release-artifact)
                              (catch Exception _e nil)))
                          
                          (when logger
                            (log/info logger :workflow :release/phase-completed
                                     {:data release-content}))
                          
                          {:success? true
                           :artifacts [release-artifact]
                           :errors []
                           :metrics {:files-written created
                                    :files-modified modified
                                    :files-deleted deleted}}))
                      
                      ;; Git staging failed
                      (do
                        (when logger
                          (log/error logger :workflow :release/git-stage-failed
                                    {:message (:error stage-result)}))
                        {:success? false
                         :artifacts []
                         :errors [{:type :git-stage-failed
                                   :message (:error stage-result)}]
                         :metrics {:files-written created
                                  :files-modified modified
                                  :files-deleted deleted}})))
                  
                  ;; File operation errors occurred
                  (do
                    (when logger
                      (log/error logger :workflow :release/file-operations-failed
                                {:data {:error-count (count errors)}}))
                    {:success? false
                     :artifacts []
                     :errors errors
                     :metrics {:files-written created
                              :files-modified modified
                              :files-deleted deleted}})))))))))

  (can-execute? [_this phase]
    (= phase :release))

  (get-phase-requirements [_this _phase]
    {:required-artifacts [:code]
     :optional-artifacts []}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  #_(require '[ai.miniforge.logging.interface :as log])
  
  ;; Create executor
  (def executor (->ReleasePhaseExecutor))
  
  ;; Mock workflow state with code artifact
  (def workflow-state
    {:workflow/id (random-uuid)
     :workflow/phase :release
     :workflow/artifacts [{:artifact/type :code
                          :artifact/content {:code/id (random-uuid)
                                            :code/files [{:path "src/example.clj"
                                                         :content "(ns example)\n(defn hello [] \"world\")"
                                                         :action :create}]}}]})
  
  ;; Mock context with worktree
  (def context
    {:worktree-path "/tmp/test-worktree"
     :logger (log/create-logger {:min-level :debug :output :human})})
  
  ;; Execute phase
  (p/execute-phase executor workflow-state context)

  ;; Check executor capabilities
  (p/can-execute? executor :release)  ; => true
  (p/can-execute? executor :implement)  ; => false

  ;; Get requirements
  (p/get-phase-requirements executor :release)
  ; => {:required-artifacts [:code], :optional-artifacts []}
  
  :end)
