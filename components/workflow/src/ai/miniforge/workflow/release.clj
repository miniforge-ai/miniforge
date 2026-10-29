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
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]
   [babashka.fs :as fs]
   [babashka.process :as process]))

;------------------------------------------------------------------------------ Layer 0

;; Result predicates
(defn ^{:stratum 0} succeeded?
  "Check if a result map indicates success."
  [result]
  (boolean (:success? result)))

;; File operation helpers
(defn ^{:stratum 0} ensure-parent-dir!
  "Create parent directories for a file path if they don't exist.
   Returns the parent directory path."
  [file-path]
  (let [parent (fs/parent file-path)]
    (when parent
      (fs/create-dirs parent))
    parent))

(defn ^{:stratum 0} delete-file!
  "Delete a file if it exists. Returns true if deleted, false if file didn't exist."
  [file-path]
  (if (fs/exists? file-path {:nofollow-links true})
    (do
      (fs/delete file-path)
      true)
    false))

(defn- ^{:stratum 0} inside-worktree?
  "Return true when writing to file-path would remain inside worktree-path.
  Both the worktree root and the target are resolved with NIO toRealPath, which
  follows symlinks exactly as the kernel does. For not-yet-existing paths, walks
  up to the deepest existing ancestor, resolves that physically, and reattaches
  the remaining suffix. A suffix containing '..' is rejected outright: the
  kernel resolves it one component at a time against directories that write
  creates and symlinks that already exist (missing/../jump/../x), which no
  lexical normalisation reproduces. Does NOT pre-normalise: that would collapse
  symlink hops like link/../x before resolution, allowing bypass. Fails closed
  on IO errors."
  [worktree-path file-path]
  (try
    (let [root     (.toRealPath
                     (java.nio.file.Paths/get (str worktree-path) (into-array String []))
                     (into-array java.nio.file.LinkOption []))
          root-str (str root)
          nio-abs  (.toAbsolutePath
                     (java.nio.file.Paths/get (str file-path) (into-array String [])))
          within?  #(let [s (str %)]
                      (or (= s root-str)
                          (.startsWith s (str root-str java.io.File/separator))))]
      (try
        (within? (.toRealPath nio-abs (into-array java.nio.file.LinkOption [])))
        (catch java.nio.file.NoSuchFileException _
          (if (java.nio.file.Files/isSymbolicLink nio-abs)
            false
            (letfn [(walk [candidate suffix]
                      (if (nil? candidate)
                        false
                        (try
                          (let [phys (.toRealPath candidate (into-array java.nio.file.LinkOption []))]
                            (and (not-any? #(= ".." (str %)) suffix)
                                 (within? (.resolve phys suffix))))
                          (catch java.nio.file.NoSuchFileException _
                            (walk (.getParent candidate)
                                  (.resolve (.getFileName candidate) suffix))))))]
              (walk (.getParent nio-abs) (.getFileName nio-abs)))))))
    (catch Exception _ false)))

;; Git operations
(defn ^{:stratum 0} stage-files!
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

;; Code artifact extraction
(defn ^{:stratum 0} extract-code-artifacts
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

(defn- ^{:stratum 0} plain-entry-name?
  "True when `path`'s last component names an entry: not absent, `.` or `..`."
  [path]
  (let [leaf (some-> (java.nio.file.Paths/get (str path) (into-array String []))
                     .getFileName
                     str)]
    (boolean (and leaf (not (#{"." ".."} leaf))))))

(defn- ^{:stratum 0} metadata-rejection
  "Error message when `action` on `full-path` would pass through a
   symlink or reach the worktree's git metadata, or nil. An agent-written
   `.git/hooks/pre-commit`, or a file in the `core.hooksPath` directory,
   runs on the host's next git command. Reuses pr-lifecycle's worktree
   guard, which refuses any path with a symlink component, asks git where
   its metadata is, and refuses every path when git cannot answer. Runs
   after the containment check, so `full-path` is inside `worktree-path`."
  [worktree-path full-path action]
  (let [relative (str (fs/relativize (fs/absolutize worktree-path) (fs/absolutize full-path)))]
    (when-not (if (= :delete action)
                (pr-lifecycle/safe-worktree-entry-path worktree-path relative)
                (pr-lifecycle/safe-worktree-path worktree-path relative))
      (str "Path rejected: " relative " passes through a symlink (agent output never"
           " needs to write through one) or reaches the worktree's git metadata"))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} entry-inside-worktree?
  "True when deleting the directory entry at file-path removes something
   inside worktree-path. Deletion removes the last component itself and
   does not follow it, so only the parent is resolved physically and the
   last component must be a plain name. inside-worktree? follows a final
   symlink and would validate where it points rather than the entry
   being removed."
  [worktree-path file-path]
  (let [parent (fs/parent file-path)]
    (and (plain-entry-name? file-path)
         (some? parent)
         (inside-worktree? worktree-path parent))))

(defn ^{:stratum 1} write-file!
  "Write content to a file, creating parent directories as needed.
   Returns the file path on success."
  [file-path content]
  (ensure-parent-dir! file-path)
  (spit (str file-path) content)
  file-path)

;------------------------------------------------------------------------------ Layer 2

;; File action processing
(defmulti ^{:stratum 2} process-file-action
  "Process a file action based on its :action keyword.
   
   Dispatches on the :action field of the file map.
   Returns a result map with :success?, :action, :path, and optional :error."
  (fn [_worktree-path file-spec _logger] (:action file-spec)))

(defmethod ^{:stratum 2} process-file-action :create
  [worktree-path {:keys [path content]} logger]
  (try
    (let [full-path (fs/path worktree-path path)
          rejection (if (inside-worktree? worktree-path full-path)
                      (metadata-rejection worktree-path full-path :create)
                      (str "Path traversal rejected: " path " escapes worktree root"))]
      (if rejection
        {:success? false
         :action :create
         :path path
         :error rejection}
        (do
          (write-file! full-path content)
          (when logger
            (log/debug logger :workflow :release/file-created
                      {:data {:path path}}))
          {:success? true
           :action :create
           :path path})))
    (catch Exception e
      (when logger
        (log/error logger :workflow :release/file-create-failed
                  {:message (.getMessage e)
                   :data {:path path}}))
      {:success? false
       :action :create
       :path path
       :error (.getMessage e)})))

(defmethod ^{:stratum 2} process-file-action :modify
  [worktree-path {:keys [path content]} logger]
  (try
    (let [full-path (fs/path worktree-path path)
          rejection (if (inside-worktree? worktree-path full-path)
                      (metadata-rejection worktree-path full-path :modify)
                      (str "Path traversal rejected: " path " escapes worktree root"))]
      (if rejection
        {:success? false
         :action :modify
         :path path
         :error rejection}
        (do
          (write-file! full-path content)
          (when logger
            (log/debug logger :workflow :release/file-modified
                      {:data {:path path}}))
          {:success? true
           :action :modify
           :path path})))
    (catch Exception e
      (when logger
        (log/error logger :workflow :release/file-modify-failed
                  {:message (.getMessage e)
                   :data {:path path}}))
      {:success? false
       :action :modify
       :path path
       :error (.getMessage e)})))

(defmethod ^{:stratum 2} process-file-action :delete
  [worktree-path {:keys [path]} logger]
  (try
    (let [full-path (fs/path worktree-path path)
          rejection (if (entry-inside-worktree? worktree-path full-path)
                      (metadata-rejection worktree-path full-path :delete)
                      (str "Path traversal rejected: " path " escapes worktree root"))]
      (if rejection
        {:success? false
         :action :delete
         :path path
         :error rejection}
        (let [deleted? (delete-file! full-path)]
          (when logger
            (log/debug logger :workflow :release/file-deleted
                      {:data {:path path :existed? deleted?}}))
          {:success? true
           :action :delete
           :path path
           :deleted? deleted?})))
    (catch Exception e
      (when logger
        (log/error logger :workflow :release/file-delete-failed
                  {:message (.getMessage e)
                   :data {:path path}}))
      {:success? false
       :action :delete
       :path path
       :error (.getMessage e)})))

(defmethod ^{:stratum 2} process-file-action :default
  [_worktree-path {:keys [path action]} logger]
  (when logger
    (log/warn logger :workflow :release/unknown-action
             {:data {:path path :action action}}))
  {:success? false
   :action action
   :path path
   :error (str "Unknown action: " action)})

;------------------------------------------------------------------------------ Layer 3

;; ReleasePhaseExecutor implementation
(defrecord ^{:stratum 3} ReleasePhaseExecutor []
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
            
            ;; Process all files from all code artifacts.
            ;; In the new environment model, files are discovered via git diff in
            ;; the worktree and stored under :release/files (the internal bridge key).
            ;; Fall back to the legacy :code/files key for backward compatibility.
            (let [results (atom {:created 0 :modified 0 :deleted 0 :errors []})
                  all-files (mapcat (fn [a] (or (:release/files a) (:code/files a)))
                                    code-artifacts)]
              
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
