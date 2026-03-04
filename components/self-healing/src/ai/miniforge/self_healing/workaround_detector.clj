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

(ns ai.miniforge.self-healing.workaround-detector
  "Automatic detection and application of workarounds for known issues.

   Safety tiers:
   - Tier 1 (auto): Start services, set env vars, create directories
   - Tier 2 (prompt-once): sudo operations, install packages
   - Tier 3 (always-prompt): Delete files, modify system configs"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.self-healing.workaround-registry :as registry]))

;;------------------------------------------------------------------------------ Layer 0
;; Result predicates

(defn- succeeded?
  "Check if a result map indicates success."
  [result]
  (boolean (:success? result)))

;; Pattern loading with workaround metadata

(defn- load-workaround-patterns
  "Load workaround patterns from resources.

   Returns: Vector of pattern maps with :id, :regex, :workaround"
  []
  (let [pattern-files ["backend-setup.edn" "workflow.edn"]]
    (->> pattern-files
         (map #(io/resource (str "error-patterns/" %)))
         (filter some?)
         (map slurp)
         (map edn/read-string)
         (mapcat :patterns)
         (filter :workaround))))

(def workaround-patterns
  "Cached workaround patterns with auto-fix metadata"
  (load-workaround-patterns))

;;------------------------------------------------------------------------------ Layer 1
;; Pattern matching

(defn- matches-workaround-pattern?
  "Check if error message matches a workaround pattern.

   Arguments:
     error-msg - String error message
     pattern - Pattern map with :regex

   Returns: Boolean"
  [error-msg pattern]
  (when (and error-msg (:regex pattern))
    (boolean (re-find (re-pattern (:regex pattern)) error-msg))))

(defn match-error-to-workaround
  "Match error to a workaround pattern.

   Arguments:
     error-result - Map with :message or exception

   Returns: Pattern map with :workaround or nil"
  [error-result]
  (let [error-msg (if (string? error-result)
                    error-result
                    (or (:message error-result)
                        (when (instance? Exception error-result)
                          (ex-message error-result))
                        (str error-result)))]
    (first (filter #(matches-workaround-pattern? error-msg %) workaround-patterns))))

;;------------------------------------------------------------------------------ Layer 2
;; User approval tracking

(defn- approval-file-path
  "Get path to workaround approval tracking file.

   Returns: String path to ~/.miniforge/workaround_approvals.edn"
  []
  (str (System/getProperty "user.home") "/.miniforge/workaround_approvals.edn"))

(defn- load-approvals
  "Load workaround approval history.

   Returns: Map of pattern-id -> approval status"
  []
  (let [path (approval-file-path)]
    (if (.exists (io/file path))
      (try
        (edn/read-string (slurp path))
        (catch Exception _
          {}))
      {})))

(defn- save-approval!
  "Save workaround approval.

   Arguments:
     pattern-id - Keyword pattern ID
     approval - :always | :never | :once"
  [pattern-id approval]
  (let [path (approval-file-path)
        approvals (assoc (load-approvals) pattern-id approval)]
    (io/make-parents path)
    (spit path (pr-str approvals))))

(defn- check-approval
  "Check if workaround is approved.

   Arguments:
     pattern-id - Keyword pattern ID
     requires-sudo? - Boolean if sudo required

   Returns: :approved | :denied | :prompt"
  [pattern-id requires-sudo?]
  (let [approvals (load-approvals)
        approval (get approvals pattern-id)]
    (case approval
      :always :approved
      :never :denied
      ;; No prior approval - prompt if sudo required
      (if requires-sudo? :prompt :approved))))

;;------------------------------------------------------------------------------ Layer 3
;; Workaround execution

(defn- execute-shell-command
  "Execute shell command and return result.

   Arguments:
     command - String command to execute

   Returns: Map with :success?, :output, :error"
  [command]
  (try
    (let [process (-> (ProcessBuilder. ["/bin/bash" "-c" command])
                     (.redirectErrorStream true)
                     (.start))
          output (slurp (.getInputStream process))
          exit-code (.waitFor process)]
      {:success? (zero? exit-code)
       :output output
       :exit-code exit-code})
    (catch Exception e
      {:success? false
       :error (ex-message e)})))

(defn- apply-shell-workaround
  "Apply shell command workaround.

   Arguments:
     workaround - Map with :command
     _pattern-id - Keyword pattern ID (unused but kept for consistency)

   Returns: Map with :success?, :message"
  [workaround _pattern-id]
  (let [command (:command workaround)
        result (execute-shell-command command)]
    (if (succeeded? result)
      {:success? true
       :message (str "Executed: " command)
       :output (:output result)}
      {:success? false
       :message (str "Failed to execute: " command)
       :error (or (:error result) (:output result))})))

(defn- apply-env-var-workaround
  "Apply environment variable workaround.

   Arguments:
     workaround - Map with :env-var
     _pattern-id - Keyword pattern ID (unused but kept for consistency)

   Returns: Map with :success?, :message"
  [workaround _pattern-id]
  ;; Note: Can't set env vars for parent process, but can suggest
  {:success? false
   :message (str "Please set environment variable: " (:env-var workaround))
   :suggestion (str "export " (:env-var workaround) "='your-value-here'")})

(defn- apply-backend-switch-workaround
  "Apply backend switch workaround.

   Arguments:
     workaround - Map with :to-backend
     _pattern-id - Keyword pattern ID (unused but kept for consistency)

   Returns: Map with :success?, :message"
  [workaround _pattern-id]
  {:success? true
   :message (str "Switching to backend: " (:to-backend workaround))
   :to-backend (:to-backend workaround)})

(defn apply-workaround
  "Apply workaround based on type.

   Arguments:
     pattern - Pattern map with :workaround and :id
     prompt-fn - Function to prompt user (fn [message options] -> response)
                 If nil, auto-approve non-sudo operations

   Returns: Map with :success?, :message, :applied?"
  ([pattern]
   (apply-workaround pattern nil))
  ([pattern prompt-fn]
   (let [workaround (:workaround pattern)
         pattern-id (:id pattern)
         requires-sudo? (:requires-sudo workaround)
         approval (check-approval pattern-id requires-sudo?)]

     (cond
       ;; Previously denied
       (= approval :denied)
       {:success? false
        :applied? false
        :message "Workaround previously denied by user"}

       ;; Previously approved or auto-approved
       (= approval :approved)
       (let [result (case (:type workaround)
                      :shell-command (apply-shell-workaround workaround pattern-id)
                      :env-var (apply-env-var-workaround workaround pattern-id)
                      :backend-switch (apply-backend-switch-workaround workaround pattern-id)
                      {:success? false
                       :message (str "Unknown workaround type: " (:type workaround))})]
         (assoc result :applied? true))

       ;; Need to prompt
       (and (= approval :prompt) prompt-fn)
       (let [message (str "Apply fix for " (:description pattern) "?\n"
                         "Command: " (:command workaround) "\n"
                         (when requires-sudo? "⚠️  Requires sudo"))
             options ["yes" "no" "always" "never"]
             response (prompt-fn message options)]
         (case response
           "always" (do (save-approval! pattern-id :always)
                       (recur pattern prompt-fn))
           "never" (do (save-approval! pattern-id :never)
                      {:success? false
                       :applied? false
                       :message "User declined workaround"})
           "yes" (let [result (apply-shell-workaround workaround pattern-id)]
                  (assoc result :applied? true))
           "no" {:success? false
                 :applied? false
                 :message "User declined workaround"}
           {:success? false
            :applied? false
            :message "Invalid response"}))

       ;; Need prompt but none provided - can't apply
       (= approval :prompt)
       {:success? false
        :applied? false
        :message "User approval required but no prompt function provided"
        :requires-prompt true
        :prompt-message (str "Apply fix: " (:command workaround))
        :requires-sudo requires-sudo?}

       :else
       {:success? false
        :applied? false
        :message "Unknown approval state"}))))

;;------------------------------------------------------------------------------ Layer 4
;; High-level API

(defn detect-and-apply-workaround
  "Detect workaround for error and apply it.

   Arguments:
     error-result - Error string or exception
     options - Map with:
       :prompt-fn - Function to prompt user (optional)
       :auto-approve - Boolean to auto-approve non-sudo ops (default true)

   Returns: Map with:
     :workaround-found? - Boolean
     :applied? - Boolean
     :success? - Boolean
     :message - String
     :pattern - Matched pattern (if found)"
  ([error-result]
   (detect-and-apply-workaround error-result {}))
  ([error-result options]
   (if-let [pattern (match-error-to-workaround error-result)]
     (let [result (apply-workaround pattern (:prompt-fn options))
           pattern-id (:id pattern)]
       ;; Track in registry
       (when pattern-id
         (when-not (registry/get-workaround-by-pattern pattern-id)
           (registry/add-workaround!
            {:error-pattern-id pattern-id
             :description (:description pattern)
             :workaround-type (get-in pattern [:workaround :type])
             :workaround-data (:workaround pattern)}))
         (when (:applied? result)
           (registry/update-workaround-stats! pattern-id (succeeded? result))))

       (merge result
              {:workaround-found? true
               :pattern pattern}))
     {:workaround-found? false
      :applied? false
      :success? false
      :message "No workaround found for this error"})))

;;------------------------------------------------------------------------------ Layer 5
;; GitHub issue integration (for future enhancement)

(defn fetch-workaround-from-github
  "Fetch workaround suggestions from GitHub issues.

   This is a placeholder for future GitHub API integration.

   Arguments:
     _error-message - String error message (unused - future implementation)
     _repo - String repo (unused - future implementation)

   Returns: Vector of suggested workarounds or nil"
  [_error-message _repo]
  ;; TODO: Implement GitHub API search for issues matching error message
  ;; Search for closed issues with labels: 'workaround', 'resolved'
  ;; Parse issue body for commands/fixes
  ;; Return structured workaround suggestions
  nil)
