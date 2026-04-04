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

(ns ai.miniforge.release-executor.core
  "Release phase executor - orchestrates the release pipeline.

   In the environment model, code changes live in the executor's git worktree.
   The release phase stages dirty files, commits, pushes, and creates a PR.
   No file writes from code artifacts — the implement agent already wrote files
   to the worktree during the implement phase."
  (:require
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.release-executor.git :as git]
   [ai.miniforge.release-executor.metadata :as metadata]
   [ai.miniforge.release-executor.result :as result]
   [ai.miniforge.release-executor.sandbox :as sandbox]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Pipeline helpers

(defn failed?
  "Check if pipeline has failed."
  [state]
  (contains? state :failure))

(defn fail
  "Mark pipeline as failed with error info."
  [state error-type error-msg & {:keys [hint]}]
  (let [logger (:logger state)]
    (when logger
      (log/error logger :release-executor error-type {:message error-msg}))
    (assoc state :failure
           (cond-> {:type error-type :message error-msg}
             hint (assoc :hint hint)))))

(defn extract-code-artifacts
  "Extract code artifacts from workflow artifacts (used for PR metadata generation)."
  [workflow-artifacts]
  (->> workflow-artifacts
       (filter #(or (= :code (:type %))
                    (= :code (:artifact/type %))))
       (map (fn [artifact]
              (or (:artifact/content artifact)
                  (:content artifact))))
       (remove nil?)))

(defn extract-workflow-data
  "Extract review and test artifacts from workflow artifacts for PR metadata.
   Returns a map with :review-artifacts and :test-artifacts."
  [workflow-artifacts]
  {:review-artifacts (metadata/extract-review-artifacts workflow-artifacts)
   :test-artifacts (metadata/extract-test-artifacts workflow-artifacts)})

;------------------------------------------------------------------------------ Layer 1
;; Pipeline steps

(defn step-validate-inputs [state]
  (cond
    (failed? state) state
    (not (:worktree-path state))
    (fail state :missing-worktree-path "Context must include :worktree-path for release phase")
    (not (:executor state))
    (fail state :missing-executor "Context must include :executor for release phase")
    (not (:environment-id state))
    (fail state :missing-environment-id "Context must include :environment-id for release phase")
    :else state))

(defn step-check-gh-auth [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [gh-auth (sandbox/check-gh-auth! (:executor state) (:environment-id state))]
      (if (:authenticated? gh-auth)
        state
        (fail state :gh-auth-failed (:error gh-auth)
              :hint (if (:available? gh-auth)
                      "Run: gh auth login"
                      "Install: brew install gh"))))))

(defn step-generate-metadata [state]
  (if (failed? state)
    state
    (if (:release-meta state)
      state ;; Already provided (e.g. by caller or test)
      (let [{:keys [releaser code-artifacts task-description context logger
                    workflow-data]} state
            release-meta (metadata/generate-release-metadata
                          releaser code-artifacts task-description context logger
                          workflow-data)]
        (if release-meta
          (assoc state :release-meta release-meta)
          (fail state :metadata-generation-failed
                "Releaser agent failed to generate release metadata"))))))

(defn step-create-branch [state]
  (if (failed? state)
    state
    (let [{:keys [release-meta executor environment-id]} state
          branch-name (:release/branch-name release-meta)
          result (sandbox/create-branch! executor environment-id branch-name)]
      (if (result/succeeded? result)
        (assoc state
               :branch (:branch result)
               :base-branch (:base-branch result))
        (fail state :branch-create-failed (:error result))))))

(defn step-stage-dirty-files
  "Stage all dirty files in the executor environment.

   In the environment model, files are already in the worktree (written by
   the implement agent); this step stages them for commit."
  [state]
  (if (failed? state)
    state
    (let [{:keys [executor environment-id]} state
          stage-r (sandbox/stage-files! executor environment-id :all)
          staged-r (when (result/succeeded? stage-r)
                     (sandbox/exec! executor environment-id "git diff --cached --name-only"))]
      (cond
        (not (result/succeeded? stage-r))
        (fail state :stage-failed (:error stage-r))

        (str/blank? (get staged-r :output ""))
        (fail state :no-files-to-stage
              "No modified files in executor environment to stage for release")

        :else
        (let [staged-count (count (remove str/blank?
                                          (str/split-lines (:output staged-r ""))))]
          (assoc state :write-metrics {:total-operations staged-count
                                       :files-written staged-count}))))))

(defn step-validate-diff
  "Validate staged diff is not destructive before committing.
   Rejects changes that delete more tests than they add or that empty files."
  [state]
  (if (failed? state)
    state
    (let [{:keys [worktree-path logger]} state
          diff-stats (git/diff-stats worktree-path)
          test-counts (git/count-test-defs worktree-path)]
      (cond
        ;; Net-negative test count — agent deleted existing tests
        (and test-counts
             (> (:removed test-counts) 0)
             (> (:removed test-counts) (:added test-counts)))
        (do
          (when logger
            (log/error logger :release-executor :diff/net-negative-tests
                       {:data {:added (:added test-counts)
                               :removed (:removed test-counts)}}))
          (fail state :destructive-diff
                (str "Diff removes more tests than it adds ("
                     (:removed test-counts) " removed, "
                     (:added test-counts) " added)")))

        ;; Deletions vastly outweigh additions (> 3:1 ratio, min 20 lines)
        (and diff-stats
             (> (:deletions diff-stats) 20)
             (> (:deletions diff-stats) (* 3 (max 1 (:additions diff-stats)))))
        (do
          (when logger
            (log/warn logger :release-executor :diff/heavily-destructive
                      {:data {:additions (:additions diff-stats)
                              :deletions (:deletions diff-stats)}}))
          (fail state :destructive-diff
                (str "Diff is heavily destructive ("
                     (:deletions diff-stats) " deletions vs "
                     (:additions diff-stats) " additions)")))

        :else
        (do
          (when logger
            (log/debug logger :release-executor :diff/validated
                       {:data (merge {} diff-stats test-counts)}))
          state)))))

(defn step-commit [state]
  (if (failed? state)
    state
    (let [{:keys [release-meta executor environment-id]} state
          result (sandbox/commit-changes! executor environment-id
                                          (:release/commit-message release-meta))]
      (if (result/succeeded? result)
        (assoc state :commit-sha (:commit-sha result))
        (fail state :commit-failed (:error result))))))

(defn step-push [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [branch executor environment-id]} state
          result (sandbox/push-branch! executor environment-id branch)]
      (if (result/succeeded? result)
        state
        (fail state :push-failed (:error result))))))

(defn step-create-pr [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [release-meta base-branch executor environment-id]} state
          result (sandbox/create-pr! executor environment-id
                                     {:title (:release/pr-title release-meta)
                                      :body (:release/pr-description release-meta)
                                      :base-branch base-branch})]
      (if (result/succeeded? result)
        (assoc state
               :pr-number (:pr-number result)
               :pr-url (:pr-url result))
        (fail state :pr-create-failed (:error result))))))

(defn step-build-artifact [state]
  (if (failed? state)
    state
    (let [{:keys [worktree-path branch base-branch commit-sha create-pr?
                  pr-number pr-url release-meta write-metrics code-artifacts]} state
          release-content (merge write-metrics
                                 {:git-staged? true
                                  :worktree-path (str worktree-path)
                                  :branch branch
                                  :base-branch base-branch
                                  :commit-sha commit-sha
                                  :pr-created? (boolean create-pr?)
                                  :pr-number pr-number
                                  :pr-url pr-url
                                  :release-metadata release-meta})
          release-artifact (artifact/build-artifact
                            {:id (random-uuid)
                             :type :release
                             :version "1.0.0"
                             :content release-content
                             :metadata {:phase :release
                                        :code-artifacts-count (count code-artifacts)}})]
      (assoc state :release-artifact release-artifact))))

(defn step-save-artifact [state]
  (if (failed? state)
    state
    (do
      (when-let [artifact-store (:artifact-store state)]
        (try
          (artifact/save! artifact-store (:release-artifact state))
          (catch Exception _e nil)))
      state)))

(defn pipeline->result
  "Convert pipeline state to phase result."
  [state]
  (let [{:keys [logger failure release-artifact write-metrics
                branch commit-sha pr-number pr-url]} state]
    (if failure
      (result/phase-failure (:type failure) (:message failure)
                            {:hint (:hint failure)
                             :metrics (or write-metrics {})})
      (do
        (when logger
          (log/info logger :release-executor :phase-completed
                    {:data {:branch branch
                            :commit commit-sha
                            :pr-url pr-url
                            :files-written (:files-written write-metrics)}}))
        (result/phase-success
         [release-artifact]
         (merge write-metrics
                {:pr-number pr-number
                 :pr-url pr-url
                 :commit-sha commit-sha
                 :branch branch}))))))

;------------------------------------------------------------------------------ Layer 2
;; Main execute function

(defn execute-release-phase
  "Execute the release phase.

   Requires an executor environment (worktree) acquired before this phase.
   Stages dirty files in the worktree, commits, pushes, and creates a PR.

   Arguments:
   - workflow-state - Workflow state with :workflow/artifacts
   - context        - Execution context with :worktree-path, :executor,
                      :environment-id, :logger, etc.
   - opts           - Options with :releaser (optional), :release-meta (optional)

   Returns:
   {:success? bool
    :artifacts [release-artifact]
    :errors []
    :metrics {...}}"
  [workflow-state context opts]
  (let [logger (:logger context)
        executor (:executor context)
        environment-id (:environment-id context)
        workflow-artifacts (:workflow/artifacts workflow-state)
        initial-state (cond-> {:logger logger
                               :worktree-path (:worktree-path context)
                               :artifact-store (:artifact-store context)
                               :context context
                               :create-pr? (get context :create-pr? true)
                               :releaser (:releaser opts)
                               :task-description (get-in workflow-state [:workflow/spec :spec/description])
                               :code-artifacts (extract-code-artifacts workflow-artifacts)
                               :workflow-data (extract-workflow-data workflow-artifacts)
                               :executor executor
                               :environment-id environment-id}
                        (:release-meta opts) (assoc :release-meta (:release-meta opts)))]

    (when logger
      (log/info logger :release-executor :phase-started
                {:data {:worktree-path (:worktree-path initial-state)
                        :create-pr? (:create-pr? initial-state)}}))

    (let [result (-> initial-state
                     step-validate-inputs
                     step-check-gh-auth
                     step-generate-metadata
                     step-create-branch
                     step-stage-dirty-files
                     step-validate-diff
                     step-commit
                     step-push
                     step-create-pr
                     step-build-artifact
                     step-save-artifact)]
      (when (failed? result)
        (spit "/tmp/miniforge-release-executor-debug.edn"
              (str (pr-str {:failure (:failure result)
                            :worktree-path (:worktree-path initial-state)
                            :create-pr? (:create-pr? initial-state)
                            :has-releaser? (boolean (:releaser initial-state))
                            :timestamp (java.util.Date.)})
                   "\n")
              :append true))
      (pipeline->result result))))
