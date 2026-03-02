(ns ai.miniforge.mcp-artifact-server.tools
  "Tool definitions and handlers for MCP artifact submission.

   Data-driven: the tool registry is pure EDN data. Builder functions are
   registered separately in `artifact-builders` keyed by tool name, so the
   registry itself contains no embedded code."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Counting helpers

(defn count-assertions
  "Count assertions in test content."
  [content]
  (+ (count (re-seq #"\(is\s" content))
     (count (re-seq #"\(are\s" content))
     (count (re-seq #"\(testing\s" content))
     (count (re-seq #"assert" content))
     (count (re-seq #"expect\(" content))))

(defn count-test-cases
  "Count test cases in test content."
  [content]
  (+ (count (re-seq #"\(deftest\s" content))
     (count (re-seq #"\(defspec\s" content))
     (count (re-seq #"def test_" content))
     (count (re-seq #"it\(" content))
     (count (re-seq #"test\(" content))))

;------------------------------------------------------------------------------ Layer 0
;; Validation

(defn validate-required-params
  "Validate required params against rules. Throws ex-info with code -32602 on failure."
  [required-params params]
  (doseq [[param-name {:keys [check msg]}] required-params]
    (let [v (get params param-name)]
      (when-not (and v (check v))
        (throw (ex-info msg {:code -32602}))))))

;------------------------------------------------------------------------------ Layer 0
;; Param extractors

(defn- non-blank
  "Return string if non-blank, else nil."
  [s]
  (when-not (str/blank? s) s))

;------------------------------------------------------------------------------ Layer 1
;; Artifact builders — named functions, one per tool

(defn build-code-artifact
  "Build a code artifact from MCP tool params."
  [params]
  (let [files (get params "files")
        summary (get params "summary")
        language (get params "language")
        tests-needed (get params "tests_needed" true)
        deps-added (get params "dependencies_added" [])]
    {:artifact {:code/id (str (random-uuid))
                :code/files (mapv (fn [f]
                                    {:path (get f "path")
                                     :content (get f "content")
                                     :action (keyword (get f "action"))})
                                  files)
                :code/summary summary
                :code/language language
                :code/tests-needed? (boolean tests-needed)
                :code/dependencies-added (vec deps-added)
                :code/created-at (str (java.time.Instant/now))}
     :message (str "Code artifact submitted successfully. "
                   (count files) " file(s) written to artifact store.")}))

(defn- build-plan-task
  "Build a single plan task from JSON params, resolving dependency UUIDs."
  [task-uuids idx t]
  (let [deps (get t "dependencies" [])
        dep-uuids (mapv #(get task-uuids %) deps)]
    (cond-> {:task/id (str (nth task-uuids idx))
             :task/description (get t "description")
             :task/type (keyword (get t "type"))}
      (seq dep-uuids)
      (assoc :task/dependencies (mapv str dep-uuids))
      (get t "acceptance_criteria")
      (assoc :task/acceptance-criteria (vec (get t "acceptance_criteria")))
      (get t "estimated_effort")
      (assoc :task/estimated-effort (keyword (get t "estimated_effort"))))))

(defn build-plan-artifact
  "Build a plan artifact from MCP tool params."
  [params]
  (let [plan-name (get params "name")
        tasks (get params "tasks")
        complexity (get params "complexity" "medium")
        risks (get params "risks" [])
        assumptions (get params "assumptions" [])
        task-uuids (vec (repeatedly (count tasks) random-uuid))
        plan-tasks (vec (map-indexed (partial build-plan-task task-uuids) tasks))]
    {:artifact {:plan/id (str (random-uuid))
                :plan/name plan-name
                :plan/tasks plan-tasks
                :plan/estimated-complexity (keyword complexity)
                :plan/risks (vec risks)
                :plan/assumptions (vec assumptions)
                :plan/created-at (str (java.time.Instant/now))}
     :message (str "Plan submitted successfully. "
                   (count tasks) " task(s) in plan '" plan-name "'.")}))

(defn- parse-test-files
  "Parse test file params into file maps."
  [files]
  (mapv (fn [f] {:path (get f "path") :content (get f "content")}) files))

(defn- aggregate-test-content
  "Aggregate all test file content into a single string."
  [files]
  (str/join "\n" (map #(get % "content" "") files)))

(defn- parse-coverage
  "Parse coverage params into a map."
  [coverage]
  (when coverage
    (cond-> {}
      (get coverage "lines")     (assoc :lines (get coverage "lines"))
      (get coverage "branches")  (assoc :branches (get coverage "branches"))
      (get coverage "functions") (assoc :functions (get coverage "functions")))))

(defn build-test-artifact
  "Build a test artifact from MCP tool params."
  [params]
  (let [files (get params "files")
        summary (get params "summary")
        test-type (get params "type" "unit")
        framework (non-blank (get params "framework"))
        coverage (parse-coverage (get params "coverage"))
        file-maps (parse-test-files files)
        all-content (aggregate-test-content files)
        assertions (count-assertions all-content)
        cases (count-test-cases all-content)]
    {:artifact (cond-> {:test/id (str (random-uuid))
                        :test/files file-maps
                        :test/type (keyword test-type)
                        :test/summary summary
                        :test/assertions-count (max 1 assertions)
                        :test/cases-count (max 1 cases)
                        :test/created-at (str (java.time.Instant/now))}
                 framework (assoc :test/framework framework)
                 coverage  (assoc :test/coverage coverage))
     :message (str "Test artifact submitted successfully. "
                   (count files) " test file(s), "
                   cases " test case(s).")}))

(defn build-release-artifact
  "Build a release artifact from MCP tool params."
  [params]
  (let [branch-name (get params "branch_name")
        commit-message (get params "commit_message")
        pr-title (get params "pr_title")
        pr-description (get params "pr_description")
        files-summary (non-blank (get params "files_summary"))]
    {:artifact (cond-> {:release/id (str (random-uuid))
                        :release/branch-name branch-name
                        :release/commit-message commit-message
                        :release/pr-title pr-title
                        :release/pr-description pr-description
                        :release/created-at (str (java.time.Instant/now))}
                 files-summary
                 (assoc :release/files-summary files-summary))
     :message (str "Release artifact submitted successfully. "
                   "Branch: " branch-name ", PR: " pr-title)}))

;------------------------------------------------------------------------------ Layer 1
;; Artifact builder dispatch (keyword → fn)

(def artifact-builders
  "Keyword dispatch map from tool name to builder function.
   Keeping this separate from the registry enables the registry to be pure data."
  {"submit_code_artifact"    build-code-artifact
   "submit_plan"             build-plan-artifact
   "submit_test_artifact"    build-test-artifact
   "submit_release_artifact" build-release-artifact})

;------------------------------------------------------------------------------ Layer 1
;; Tool registry — pure data, no embedded functions

(def tool-registry
  "Data-driven tool registry. Each entry is a map with:
   - :tool-def — MCP tool definition (name, description, inputSchema)
   - :required-params — validation rules {param-name {:check fn :msg string}}"
  {"submit_code_artifact"
   {:tool-def
    {:name "submit_code_artifact"
     :description "Submit a code implementation artifact. Use this tool to deliver your code output instead of printing raw EDN text. Each file must include a path, content, and action (:create, :modify, or :delete)."
     :inputSchema
     {:type "object"
      :required ["files" "summary"]
      :properties
      {"files"
       {:type "array"
        :description "Array of files to create/modify/delete"
        :items {:type "object"
                :required ["path" "content" "action"]
                :properties
                {"path"    {:type "string" :description "File path relative to project root"}
                 "content" {:type "string" :description "Full file content"}
                 "action"  {:type "string" :enum ["create" "modify" "delete"]
                            :description "File action: create, modify, or delete"}}}}
       "summary"
       {:type "string"
        :description "Brief description of the changes made"}
       "language"
       {:type "string"
        :description "Primary programming language (e.g. clojure, python, javascript)"}
       "tests_needed"
       {:type "boolean"
        :description "Whether tests should be written for this code"}
       "dependencies_added"
       {:type "array"
        :description "List of dependencies added (e.g. [\"lib/name {:mvn/version \\\"1.0.0\\\"}\"])"
        :items {:type "string"}}}}}

    :required-params
    {"files"   {:check seq              :msg "files is required and must not be empty"}
     "summary" {:check (complement str/blank?) :msg "summary is required"}}}

   "submit_plan"
   {:tool-def
    {:name "submit_plan"
     :description "Submit an implementation plan artifact. Use this tool to deliver your plan instead of printing raw EDN text. Each task must include a description and type."
     :inputSchema
     {:type "object"
      :required ["name" "tasks"]
      :properties
      {"name"
       {:type "string"
        :description "Short descriptive name for the plan"}
       "tasks"
       {:type "array"
        :description "Array of plan tasks"
        :items {:type "object"
                :required ["description" "type"]
                :properties
                {"description"
                 {:type "string" :description "What to do in this task"}
                 "type"
                 {:type "string"
                  :enum ["implement" "test" "review" "design" "deploy" "configure"]
                  :description "Task type"}
                 "dependencies"
                 {:type "array"
                  :description "IDs of tasks this depends on (indexes into tasks array)"
                  :items {:type "integer"}}
                 "acceptance_criteria"
                 {:type "array"
                  :description "Verifiable criteria for task completion"
                  :items {:type "string"}}
                 "estimated_effort"
                 {:type "string"
                  :enum ["small" "medium" "large" "xlarge"]
                  :description "Estimated effort level"}}}}
       "complexity"
       {:type "string"
        :enum ["low" "medium" "high"]
        :description "Overall estimated complexity"}
       "risks"
       {:type "array"
        :description "Potential risks or blockers"
        :items {:type "string"}}
       "assumptions"
       {:type "array"
        :description "Assumptions made during planning"
        :items {:type "string"}}}}}

    :required-params
    {"name"  {:check (complement str/blank?) :msg "name is required"}
     "tasks" {:check seq                     :msg "tasks is required and must not be empty"}}}

   "submit_test_artifact"
   {:tool-def
    {:name "submit_test_artifact"
     :description "Submit a test artifact. Use this tool to deliver your test output instead of printing raw EDN text. Each file must include a path and content."
     :inputSchema
     {:type "object"
      :required ["files" "summary"]
      :properties
      {"files"
       {:type "array"
        :description "Array of test files"
        :items {:type "object"
                :required ["path" "content"]
                :properties
                {"path"    {:type "string" :description "Test file path relative to project root"}
                 "content" {:type "string" :description "Full test file content"}}}}
       "summary"
       {:type "string"
        :description "Description of test coverage"}
       "type"
       {:type "string"
        :enum ["unit" "integration" "property" "e2e" "acceptance"]
        :description "Type of tests"}
       "framework"
       {:type "string"
        :description "Testing framework used (e.g. clojure.test, pytest, jest)"}
       "coverage"
       {:type "object"
        :description "Coverage information"
        :properties
        {"lines"     {:type "number" :description "Line coverage percentage"}
         "branches"  {:type "number" :description "Branch coverage percentage"}
         "functions" {:type "number" :description "Function coverage percentage"}}}}}}

    :required-params
    {"files"   {:check seq              :msg "files is required and must not be empty"}
     "summary" {:check (complement str/blank?) :msg "summary is required"}}}

   "submit_release_artifact"
   {:tool-def
    {:name "submit_release_artifact"
     :description "Submit a release artifact. Use this tool to deliver your release metadata instead of printing raw EDN text."
     :inputSchema
     {:type "object"
      :required ["branch_name" "commit_message" "pr_title" "pr_description"]
      :properties
      {"branch_name"
       {:type "string"
        :description "Git branch name (e.g. feature/add-auth)"}
       "commit_message"
       {:type "string"
        :description "Git commit message (conventional commits format)"}
       "pr_title"
       {:type "string"
        :description "Pull request title (max 70 characters)"}
       "pr_description"
       {:type "string"
        :description "Pull request description in markdown"}
       "files_summary"
       {:type "string"
        :description "Brief summary of files changed (e.g. '2 files created, 1 modified')"}}}}

    :required-params
    {"branch_name"    {:check (complement str/blank?) :msg "branch_name is required"}
     "commit_message" {:check (complement str/blank?) :msg "commit_message is required"}
     "pr_title"       {:check (complement str/blank?) :msg "pr_title is required"}
     "pr_description" {:check (complement str/blank?) :msg "pr_description is required"}}}})

;------------------------------------------------------------------------------ Layer 2
;; Tool definitions list (derived from registry)

(def tool-definitions
  "MCP tool definitions derived from the registry."
  (mapv :tool-def (vals tool-registry)))

;------------------------------------------------------------------------------ Layer 2
;; Generic tool handler

(defn handle-tool-call
  "Handle an MCP tools/call request.

   Arguments:
   - tool-name — string name of the tool
   - arguments — map of tool arguments
   - write-artifact-fn — fn [artifact] that persists the artifact, returns path

   Returns {:content [{:type \"text\" :text message}]}
   Throws on unknown tool or validation failure."
  [tool-name arguments write-artifact-fn]
  (let [{:keys [required-params]} (get tool-registry tool-name)
        build-artifact (get artifact-builders tool-name)]
    (when-not build-artifact
      (throw (ex-info (str "Unknown tool: " tool-name) {:code -32601})))
    (validate-required-params required-params arguments)
    (let [{:keys [artifact message]} (build-artifact arguments)
          path (write-artifact-fn artifact)]
      (binding [*out* *err*]
        (println "Artifact written:" path))
      {:content [{:type "text" :text message}]})))
