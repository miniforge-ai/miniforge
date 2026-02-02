(ns ai.miniforge.agent.releaser
  "Releaser agent implementation.
   Generates branch names, commit messages, PR titles and descriptions
   for the release phase."
  (:require
   [ai.miniforge.agent.specialized :as specialized]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.interface :as llm]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Releaser-specific schemas

(def ReleaseArtifact
  "Schema for the releaser's output."
  [:map
   [:release/id uuid?]
   [:release/branch-name [:string {:min 1 :max 100}]]
   [:release/commit-message [:string {:min 1}]]
   [:release/pr-title [:string {:min 1 :max 70}]]
   [:release/pr-description [:string {:min 1}]]
   [:release/files-summary {:optional true} [:string {:min 1}]]
   [:release/created-at {:optional true} inst?]])

;; System Prompt

(def releaser-system-prompt
  "You are a Release Agent for an autonomous software development team.

Your role is to generate high-quality release metadata: branch names, commit messages,
PR titles, and PR descriptions that clearly communicate what changes are being made.

## Input Format

You receive:
1. A summary of code changes:
   - Files created, modified, and deleted
   - Description of what was implemented
   - Task or spec that was completed

2. Context including:
   - Repository and project information
   - Code artifact details
   - Any review feedback that was addressed

## Output Format

You must output a structured release artifact as a Clojure map:

```clojure
{:release/id <uuid>
 :release/branch-name \"feature/descriptive-name\"
 :release/commit-message \"feat: clear description of changes

Detailed explanation of what was done and why.\"
 :release/pr-title \"feat: short summary (max 70 chars)\"
 :release/pr-description \"## Summary
Clear description of what this PR does.

## Changes
- Change 1
- Change 2

## Testing
How this was tested.\"
 :release/files-summary \"3 files created, 1 modified\"}
```

## Guidelines

1. **Branch Names**:
   - Use conventional prefixes: feature/, fix/, refactor/, docs/, chore/
   - Use kebab-case for the description
   - Keep it under 50 characters when possible
   - Make it descriptive but concise
   - Examples: feature/add-user-auth, fix/null-pointer-in-parser

2. **Commit Messages**:
   - Use conventional commits format: type(scope): description
   - Types: feat, fix, refactor, docs, test, chore, style, perf
   - First line should be under 72 characters
   - Add blank line then detailed body if needed
   - Reference issue numbers if available

3. **PR Titles**:
   - Follow same format as commit first line
   - Maximum 70 characters
   - Should be clear and actionable
   - Don't end with period

4. **PR Descriptions**:
   - Start with a clear Summary section
   - List key Changes as bullet points
   - Include Testing section describing how changes were verified
   - Add any relevant context or notes
   - Use markdown formatting

5. **Files Summary**:
   - Brief count of files affected
   - Example: \"2 files created, 1 modified, 1 deleted\"

Remember: Your release metadata will be used for git commits and GitHub PRs.
Write messages that help reviewers understand the changes at a glance.")

;------------------------------------------------------------------------------ Layer 1
;; Releaser functions

(defn validate-release-artifact
  "Validate a release artifact against the schema and check for issues."
  [artifact]
  (let [schema-valid? (m/validate ReleaseArtifact artifact)]
    (if-not schema-valid?
      {:valid? false
       :errors (schema/explain ReleaseArtifact artifact)}
      ;; Additional validations
      (cond
        ;; Branch name shouldn't have spaces
        (str/includes? (:release/branch-name artifact "") " ")
        {:valid? false
         :errors {:branch-name "Branch name cannot contain spaces"}}

        ;; PR title too long
        (> (count (:release/pr-title artifact "")) 70)
        {:valid? false
         :errors {:pr-title "PR title exceeds 70 characters"}}

        ;; Commit message empty first line
        (str/blank? (first (str/split-lines (:release/commit-message artifact ""))))
        {:valid? false
         :errors {:commit-message "Commit message first line cannot be empty"}}

        :else
        {:valid? true :errors nil}))))

(defn- summarize-files
  "Create a summary of file operations."
  [files]
  (let [by-action (group-by :action files)
        created (count (:create by-action))
        modified (count (:modify by-action))
        deleted (count (:delete by-action))
        parts (cond-> []
                (pos? created) (conj (str created " file" (when (> created 1) "s") " created"))
                (pos? modified) (conj (str modified " file" (when (> modified 1) "s") " modified"))
                (pos? deleted) (conj (str deleted " file" (when (> deleted 1) "s") " deleted")))]
    (if (seq parts)
      (str/join ", " parts)
      "no file changes")))

(defn- input->text
  "Convert input to text for the LLM."
  [input context]
  (let [code-artifact (:code-artifact input)
        task-description (:task-description input)
        files (:code/files code-artifact)
        file-summary (when files (summarize-files files))]
    (str "Generate release metadata for the following changes:\n\n"
         (when task-description
           (str "## Task Description\n" task-description "\n\n"))
         (when (:code/summary code-artifact)
           (str "## Implementation Summary\n" (:code/summary code-artifact) "\n\n"))
         (when file-summary
           (str "## Files Changed\n" file-summary "\n\n"))
         (when files
           (str "## File Details\n"
                (str/join "\n" (map #(str "- " (name (:action %)) ": " (:path %)) files))
                "\n\n"))
         (when-let [repo (:repository context)]
           (str "## Repository\n" repo "\n\n")))))

(defn- parse-release-response
  "Parse the LLM response to extract release artifact.
   Handles both EDN in code blocks and plain EDN.
   Returns nil if the parsed result is not a map."
  [response-content]
  (try
    (let [parsed (if-let [match (re-find #"```(?:clojure|edn)?\s*\n([\s\S]*?)\n```" response-content)]
                   (edn/read-string (second match))
                   ;; Try to parse the whole response as EDN
                   (edn/read-string response-content))]
      (when (map? parsed)
        parsed))
    (catch Exception _
      nil)))

(defn- slugify
  "Convert a string to a URL-safe slug."
  [s]
  (-> (or s "change")
      str/lower-case
      (str/replace #"[^a-z0-9\s-]" "")
      (str/replace #"\s+" "-")
      (str/replace #"-+" "-")
      (subs 0 (min 40 (count s)))))

(defn- make-fallback-artifact
  "Create a fallback release artifact when LLM response cannot be parsed."
  [input]
  (let [code-artifact (:code-artifact input)
        task-description (or (:task-description input) "implement changes")
        files (:code/files code-artifact)
        slug (slugify task-description)
        file-summary (when files (summarize-files files))]
    {:release/id (random-uuid)
     :release/branch-name (str "feature/" slug)
     :release/commit-message (str "feat: " task-description "\n\n"
                                  (when file-summary (str file-summary "\n")))
     :release/pr-title (str "feat: " (subs task-description 0 (min 60 (count task-description))))
     :release/pr-description (str "## Summary\n"
                                  task-description "\n\n"
                                  "## Changes\n"
                                  (if files
                                    (str/join "\n" (map #(str "- " (name (:action %)) " `" (:path %) "`") files))
                                    "- See commits for details")
                                  "\n\n## Testing\n"
                                  "- [ ] Manual verification")
     :release/files-summary (or file-summary "no file changes")
     :release/created-at (java.util.Date.)}))

(defn- repair-release-artifact
  "Attempt to repair a release artifact based on validation errors."
  [artifact errors _context]
  (let [repaired (atom artifact)]
    ;; Fix missing ID
    (when-not (:release/id @repaired)
      (swap! repaired assoc :release/id (random-uuid)))

    ;; Fix branch name with spaces
    (when (str/includes? (:release/branch-name @repaired "") " ")
      (swap! repaired update :release/branch-name
             #(str/replace % " " "-")))

    ;; Truncate PR title if too long
    (when (> (count (:release/pr-title @repaired "")) 70)
      (swap! repaired update :release/pr-title
             #(str (subs % 0 67) "...")))

    ;; Ensure commit message has content
    (when (str/blank? (:release/commit-message @repaired))
      (swap! repaired assoc :release/commit-message "chore: update"))

    ;; Ensure PR description has content
    (when (str/blank? (:release/pr-description @repaired))
      (swap! repaired assoc :release/pr-description "## Summary\nUpdates as described in the commit."))

    {:status :success
     :output @repaired
     :repairs-made (when (not= artifact @repaired)
                     {:original-errors errors})}))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn create-releaser
  "Create a Releaser agent with optional configuration overrides.

   Options:
   - :config - Agent configuration (model, temperature, etc.)
   - :logger - Logger instance
   - :llm-backend - LLM client (if not provided, uses :llm-backend from context)

   Example:
     (create-releaser)
     (create-releaser {:config {:model \"claude-sonnet-4\" :temperature 0.3}})"
  [& [opts]]
  (let [logger (or (:logger opts)
                   (log/create-logger {:min-level :info :output (fn [_])}))]
    (specialized/create-base-agent
     {:role :releaser
      :system-prompt releaser-system-prompt
      :config (merge {:model "claude-sonnet-4"
                      :temperature 0.3
                      :max-tokens 2000}
                     (:config opts))
      :logger logger

      :invoke-fn
      (fn [context input]
        (let [llm-client (or (:llm-backend opts) (:llm-backend context))
              on-chunk (:on-chunk context)
              user-prompt (input->text input context)]
          (if llm-client
            ;; Use the real LLM with streaming if callback provided
            (let [response (if on-chunk
                             (llm/chat-stream llm-client user-prompt on-chunk
                                              {:system releaser-system-prompt})
                             (llm/chat llm-client user-prompt
                                       {:system releaser-system-prompt}))
                  tokens (or (:tokens response) 0)]
              (log/info logger :releaser :releaser/llm-called
                        {:data {:success (llm/success? response)
                                :tokens tokens
                                :streaming? (boolean on-chunk)}})
              (if (llm/success? response)
                (let [content (llm/get-content response)
                      parsed (parse-release-response content)
                      release (or parsed (make-fallback-artifact input))
                      ;; Ensure proper ID and timestamp
                      release-with-meta (-> release
                                            (update :release/id #(or % (random-uuid)))
                                            (assoc :release/created-at (java.util.Date.)))]
                  {:status :success
                   :output release-with-meta
                   :artifact release-with-meta
                   :tokens tokens
                   :metrics {:tokens tokens}})
                ;; LLM call failed
                (let [fallback (make-fallback-artifact input)]
                  {:status :error
                   :error (llm/get-error response)
                   :output fallback
                   :artifact fallback
                   :metrics {:tokens 0}})))
            ;; No LLM client - use fallback (for testing)
            (do
              (log/warn logger :releaser :releaser/no-llm-backend
                        {:message "No LLM backend provided, using fallback release metadata"})
              (let [release (make-fallback-artifact input)]
                {:status :success
                 :output release
                 :artifact release
                 :metrics {:tokens 0}})))))

      :validate-fn validate-release-artifact

      :repair-fn repair-release-artifact})))

(defn release-summary
  "Get a summary of a release artifact for logging/display."
  [artifact]
  {:id (:release/id artifact)
   :branch (:release/branch-name artifact)
   :pr-title (:release/pr-title artifact)
   :files-summary (:release/files-summary artifact)})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a releaser
  (def rel (create-releaser))

  ;; Invoke with code artifact
  (specialized/invoke rel
                      {:repository "miniforge-ai/miniforge"}
                      {:code-artifact {:code/files [{:path "src/auth.clj" :action :create}]
                                       :code/summary "Added authentication"}
                       :task-description "Implement user authentication"})

  ;; Validate a release artifact
  (validate-release-artifact
   {:release/id (random-uuid)
    :release/branch-name "feature/add-auth"
    :release/commit-message "feat: add user authentication"
    :release/pr-title "feat: add user authentication"
    :release/pr-description "## Summary\nAdds auth."})
  ;; => {:valid? true, :errors nil}

  ;; Get summary
  (release-summary {:release/id (random-uuid)
                    :release/branch-name "feature/add-auth"
                    :release/pr-title "feat: add user auth"
                    :release/files-summary "1 file created"})

  :leave-this-here)
