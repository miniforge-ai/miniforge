;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.gate.precommit-discipline
  "Pre-commit hook discipline policy gate.
  
   Enforces .cursor/rules/700-workflows/715-pre-commit-discipline.mdc by:
   1. Checking git history for commits made with --no-verify
   2. Validating that bypassed commits have proper [BYPASS-HOOKS: reason] documentation
   3. Verifying manual validation steps were documented
   4. Flagging violations with appropriate severity
   
   This gate helps maintain code quality by ensuring pre-commit hooks are not
   bypassed without proper justification and manual validation."
  (:require [ai.miniforge.gate.registry :as registry]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;;------------------------------------------------------------------------------ Layer 0
;; Git history analysis

(defn- exec-git
  "Execute a git command and return output.
  
   Arguments:
     args - Vector of git command arguments
     
   Returns:
     {:exit int :out string :err string}"
  [args]
  (try
    (apply shell/sh "git" args)
    (catch Exception ex
      {:exit 1
       :out ""
       :err (ex-message ex)})))

(defn- get-recent-commits
  "Get recent commits with full messages.
  
   Arguments:
     limit - Number of commits to retrieve (default 50)
     branch - Branch to check (default HEAD)
     
   Returns:
     Vector of {:hash string :message string :author string :date string}"
  [& {:keys [limit branch] :or {limit 50 branch "HEAD"}}]
  (let [format "%H|||%s|||%b|||%an|||%ai"
        result (exec-git ["log" (str "-" limit) "--format=" format branch])]
    (if (zero? (:exit result))
      (->> (str/split (:out result) #"\n(?=[0-9a-f]{40}|||)")
           (keep (fn [commit-str]
                   (when-not (str/blank? commit-str)
                     (let [[hash subject body author date] (str/split commit-str #"\|\|\|" 5)]
                       {:hash hash
                        :subject (str/trim subject)
                        :body (str/trim (or body ""))
                        :message (str/trim (str subject "\n" (or body "")))
                        :author (str/trim author)
                        :date (str/trim date)}))))
           vec)
      [])))

(defn- check-no-verify-in-history?
  "Check if a commit was likely made with --no-verify.

   Since --no-verify doesn't leave direct traces in git history, we use heuristics:
   - Commit message contains [BYPASS-HOOKS:] or similar markers
   - Commit message explicitly mentions --no-verify
   - Context clues about bypassing pre-commit hooks

   Arguments:
     commit - Commit map with :message

   Returns:
     Boolean indicating if commit appears to have bypassed hooks"
  [commit]
  (let [msg (:message commit)
        msg-lower (str/lower-case msg)]
    (boolean
     (or
      ;; Explicit bypass documentation
      (re-find #"\[BYPASS-HOOKS:" msg)
      (re-find #"--no-verify" msg)
      (re-find #"skip.*hooks?" msg-lower)
      (re-find #"bypass.*pre-?commit" msg-lower)))))

(defn- parse-bypass-reason
  "Extract bypass reason from commit message.
  
   Arguments:
     message - Commit message string
     
   Returns:
     String with bypass reason, or nil if not found"
  [message]
  (when-let [match (re-find #"\[BYPASS-HOOKS:\s*([^\]]+)\]" message)]
    (str/trim (second match))))

(defn- check-manual-validation
  "Check if commit message documents manual validation steps.
  
   Arguments:
     message - Commit message string
     
   Returns:
     {:documented? bool :steps [string]}"
  [message]
  (let [manual-section (re-find #"Manual validation:\s*([\s\S]*?)(?=\n\n|\Z)" message)
        validation-lines (when manual-section
                          (->> (str/split (second manual-section) #"\n")
                               (map str/trim)
                               (filter #(str/starts-with? % "-"))
                               (mapv #(str/replace % #"^-\s*" ""))))]
    {:documented? (boolean (seq validation-lines))
     :steps (or validation-lines [])}))

(defn- validate-bypass-commit
  "Validate a commit that bypassed pre-commit hooks.
  
   According to 715-pre-commit-discipline.mdc, bypassed commits must:
   1. Have [BYPASS-HOOKS: reason] in the message
   2. Document manual validation steps
   3. Explain why bypass was necessary
   4. Be on a feature branch (not main)
   
   Arguments:
     commit - Commit map
     
   Returns:
     {:valid? bool :violations [{:severity :error/:warning :message string}]}"
  [commit]
  (let [message (:message commit)
        bypass-reason (parse-bypass-reason message)
        manual-validation (check-manual-validation message)
        has-root-cause? (re-find #"Root cause:" message)
        has-why-bypass? (re-find #"Why bypass:" message)
        
        violations (cond-> []
                     ;; Critical: No bypass documentation
                     (nil? bypass-reason)
                     (conj {:severity :error
                            :message "Commit bypassed pre-commit hooks without [BYPASS-HOOKS: reason] documentation"
                            :commit-hash (:hash commit)
                            :commit-subject (:subject commit)})
                     
                     ;; Critical: No manual validation documented
                     (and bypass-reason (not (:documented? manual-validation)))
                     (conj {:severity :error
                            :message "Bypassed commit missing manual validation documentation"
                            :commit-hash (:hash commit)
                            :commit-subject (:subject commit)
                            :help "Expected format: 'Manual validation:\n- Tests passed: ...\n- Linting passed: ...'"})
                     
                     ;; Warning: Incomplete bypass justification
                     (and bypass-reason (not has-root-cause?))
                     (conj {:severity :warning
                            :message "Bypassed commit should document 'Root cause:' of hook failure"
                            :commit-hash (:hash commit)
                            :commit-subject (:subject commit)})
                     
                     ;; Warning: Missing explanation of why bypass was necessary
                     (and bypass-reason (not has-why-bypass?))
                     (conj {:severity :warning
                            :message "Bypassed commit should document 'Why bypass:' was necessary"
                            :commit-hash (:hash commit)
                            :commit-subject (:subject commit)}))]
    
    {:valid? (empty? (filter #(= :error (:severity %)) violations))
     :violations violations}))

;;------------------------------------------------------------------------------ Layer 1
;; Gate implementation

(defn- check-precommit-discipline
  "Check pre-commit discipline across recent git history.
  
   Arguments:
     artifact - Artifact to validate (typically workflow context)
     ctx      - Execution context with optional :config
     
   Context config options:
     :commits-to-check - Number of recent commits to check (default 50)
     :branch          - Branch to check (default HEAD)
     :fail-on-warning - Fail gate on warnings (default false)
     
   Returns:
     {:passed? bool :errors [...] :warnings [...]}"
  [_artifact ctx]
  (let [config (or (:config ctx) {})
        commits-to-check (get config :commits-to-check 50)
        branch (get config :branch "HEAD")
        fail-on-warning? (get config :fail-on-warning false)
        
        ;; Get recent commits
        commits (get-recent-commits :limit commits-to-check :branch branch)
        
        ;; Find commits that bypassed hooks
        bypassed-commits (filter check-no-verify-in-history? commits)
        
        ;; Validate each bypassed commit
        validations (map validate-bypass-commit bypassed-commits)
        
        ;; Collect all violations
        all-violations (mapcat :violations validations)
        errors (filter #(= :error (:severity %)) all-violations)
        warnings (filter #(= :warning (:severity %)) all-violations)
        
        ;; Build result
        has-errors? (seq errors)
        has-warnings? (seq warnings)
        passed? (and (not has-errors?)
                    (or (not fail-on-warning?) (not has-warnings?)))]
    
    (cond-> {:passed? passed?}
      has-errors?
      (assoc :errors (mapv (fn [v]
                            {:type :precommit-discipline-violation
                             :message (:message v)
                             :location {:commit (:commit-hash v)
                                       :subject (:commit-subject v)}
                             :help (:help v)})
                          errors))
      
      has-warnings?
      (assoc :warnings (mapv (fn [v]
                              {:type :precommit-discipline-warning
                               :message (:message v)
                               :location {:commit (:commit-hash v)
                                         :subject (:commit-subject v)}
                               :help (:help v)})
                            warnings))
      
      (seq bypassed-commits)
      (assoc :metadata {:bypassed-commits-found (count bypassed-commits)
                        :total-commits-checked (count commits)}))))

(defn- repair-precommit-discipline
  "Attempt to repair pre-commit discipline violations.
  
   Pre-commit discipline violations cannot be automatically repaired because:
   1. Git history cannot be rewritten in shared branches
   2. Proper documentation requires human judgment
   3. Manual validation steps must be actually performed
   
   Arguments:
     artifact - Artifact to repair
     errors   - Errors from check
     ctx      - Execution context
     
   Returns:
     {:success? false :message string}"
  [artifact errors _ctx]
  {:success? false
   :artifact artifact
   :errors errors
   :message "Pre-commit discipline violations cannot be automatically repaired.
             
             To fix:
             1. Review the flagged commits and their documentation
             2. If commit is recent and local, amend with proper documentation
             3. If commit is pushed, add corrective commit with proper practices
             4. Ensure future commits follow pre-commit discipline guidelines
             
             See .cursor/rules/700-workflows/715-pre-commit-discipline.mdc for details."})

;;------------------------------------------------------------------------------ Layer 2
;; Registry integration

(registry/register-gate! :precommit-discipline)

(defmethod registry/get-gate :precommit-discipline
  [_]
  {:name :precommit-discipline
   :description "Validates pre-commit hook discipline and bypass documentation"
   :check check-precommit-discipline
   :repair repair-precommit-discipline})

;;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Check recent commits for pre-commit discipline
  (check-precommit-discipline {} {:config {:commits-to-check 10}})
  
  ;; Get recent commits
  (get-recent-commits :limit 5)
  
  ;; Check if a commit bypassed hooks
  (check-no-verify-in-history? 
   {:message "fix: emergency hotfix\n\n[BYPASS-HOOKS: EMERGENCY]\nProduction down..."})
  
  ;; Parse bypass reason
  (parse-bypass-reason "fix: something\n\n[BYPASS-HOOKS: environmental issue]\n...")
  
  ;; Check manual validation
  (check-manual-validation 
   "fix: thing\n\nManual validation:\n- Tests passed: yes\n- Linting passed: yes")
  
  ;; Validate a bypassed commit
  (validate-bypass-commit
   {:hash "abc123"
    :subject "fix: emergency fix"
    :message "fix: emergency fix\n\n[BYPASS-HOOKS: emergency]\n\nManual validation:\n- Tests passed"})
  
  :leave-this-here)
