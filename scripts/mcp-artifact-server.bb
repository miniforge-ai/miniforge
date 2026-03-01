#!/usr/bin/env bb
;; MCP Artifact Submission Server
;; ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
;;
;; Standalone MCP server (JSON-RPC 2.0 over stdin/stdout) that exposes
;; artifact submission tools. When invoked by the Claude CLI via --mcp-config,
;; the LLM calls these tools instead of outputting raw EDN text. The server
;; writes the artifact to a known file in the artifact directory for the agent
;; to read after the CLI exits.
;;
;; The server is data-driven: adding a new tool requires only a new entry in
;; `tool-registry`. No new handler functions needed.
;;
;; Usage:
;;   bb scripts/mcp-artifact-server.bb --artifact-dir /tmp/artifact-session-xyz

(require '[cheshire.core :as json]
         '[clojure.string :as str])

;; --------------------------------------------------------------------------- CLI args

(defn parse-args [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (case (first args)
        "--artifact-dir" (recur (drop 2 args)
                                (assoc opts :artifact-dir (second args)))
        (recur (rest args) opts)))))

(def cli-opts (parse-args *command-line-args*))
(def artifact-dir (:artifact-dir cli-opts))

(when-not artifact-dir
  (binding [*out* *err*]
    (println "ERROR: --artifact-dir is required"))
  (System/exit 1))

;; --------------------------------------------------------------------------- JSON-RPC helpers

(defn write-response [id result]
  (let [msg (json/generate-string {:jsonrpc "2.0" :id id :result result})]
    (println msg)
    (flush)))

(defn write-error [id code message]
  (let [msg (json/generate-string {:jsonrpc "2.0" :id id
                                   :error {:code code :message message}})]
    (println msg)
    (flush)))

;; --------------------------------------------------------------------------- Artifact persistence

(defn write-artifact! [artifact]
  (let [path (str artifact-dir "/artifact.edn")]
    (spit path (pr-str artifact))
    path))

;; --------------------------------------------------------------------------- Assertion/test counting helpers

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

;; --------------------------------------------------------------------------- Tool registry

(def tool-registry
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
    {"files"   {:check #(seq %)              :msg "files is required and must not be empty"}
     "summary" {:check #(not (str/blank? %)) :msg "summary is required"}}

    :build-artifact
    (fn [params]
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
                       (count files) " file(s) written to artifact store.")}))}

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
    {"name"  {:check #(not (str/blank? %)) :msg "name is required"}
     "tasks" {:check #(seq %)              :msg "tasks is required and must not be empty"}}

    :build-artifact
    (fn [params]
      (let [plan-name (get params "name")
            tasks (get params "tasks")
            complexity (get params "complexity" "medium")
            risks (get params "risks" [])
            assumptions (get params "assumptions" [])
            task-uuids (vec (repeatedly (count tasks) random-uuid))
            plan-tasks (vec (map-indexed
                             (fn [idx t]
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
                             tasks))]
        {:artifact {:plan/id (str (random-uuid))
                    :plan/name plan-name
                    :plan/tasks plan-tasks
                    :plan/estimated-complexity (keyword complexity)
                    :plan/risks (vec risks)
                    :plan/assumptions (vec assumptions)
                    :plan/created-at (str (java.time.Instant/now))}
         :message (str "Plan submitted successfully. "
                       (count tasks) " task(s) in plan '" plan-name "'.")}))}

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
    {"files"   {:check #(seq %)              :msg "files is required and must not be empty"}
     "summary" {:check #(not (str/blank? %)) :msg "summary is required"}}

    :build-artifact
    (fn [params]
      (let [files (get params "files")
            summary (get params "summary")
            test-type (get params "type" "unit")
            framework (get params "framework")
            coverage (get params "coverage")
            file-maps (mapv (fn [f]
                              {:path (get f "path")
                               :content (get f "content")})
                            files)
            all-content (str/join "\n" (map #(get % "content" "") files))
            assertions (count-assertions all-content)
            cases (count-test-cases all-content)]
        {:artifact (cond-> {:test/id (str (random-uuid))
                            :test/files file-maps
                            :test/type (keyword test-type)
                            :test/summary summary
                            :test/assertions-count (max 1 assertions)
                            :test/cases-count (max 1 cases)
                            :test/created-at (str (java.time.Instant/now))}
                     framework
                     (assoc :test/framework framework)
                     coverage
                     (assoc :test/coverage
                            (cond-> {}
                              (get coverage "lines")     (assoc :lines (get coverage "lines"))
                              (get coverage "branches")  (assoc :branches (get coverage "branches"))
                              (get coverage "functions") (assoc :functions (get coverage "functions")))))
         :message (str "Test artifact submitted successfully. "
                       (count files) " test file(s), "
                       cases " test case(s).")}))}

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
    {"branch_name"    {:check #(not (str/blank? %)) :msg "branch_name is required"}
     "commit_message" {:check #(not (str/blank? %)) :msg "commit_message is required"}
     "pr_title"       {:check #(not (str/blank? %)) :msg "pr_title is required"}
     "pr_description" {:check #(not (str/blank? %)) :msg "pr_description is required"}}

    :build-artifact
    (fn [params]
      (let [branch-name (get params "branch_name")
            commit-message (get params "commit_message")
            pr-title (get params "pr_title")
            pr-description (get params "pr_description")
            files-summary (get params "files_summary")]
        {:artifact (cond-> {:release/id (str (random-uuid))
                            :release/branch-name branch-name
                            :release/commit-message commit-message
                            :release/pr-title pr-title
                            :release/pr-description pr-description
                            :release/created-at (str (java.time.Instant/now))}
                     files-summary
                     (assoc :release/files-summary files-summary))
         :message (str "Release artifact submitted successfully. "
                       "Branch: " branch-name ", PR: " pr-title)}))}})

;; --------------------------------------------------------------------------- Generic tool handler

(defn validate-required-params [required-params params]
  (doseq [[param-name {:keys [check msg]}] required-params]
    (let [v (get params param-name)]
      (when-not (and v (check v))
        (throw (ex-info msg {:code -32602}))))))

(defn handle-tool-call [tool-name arguments]
  (if-let [{:keys [required-params build-artifact]} (get tool-registry tool-name)]
    (do
      (validate-required-params required-params arguments)
      (let [{:keys [artifact message]} (build-artifact arguments)
            path (write-artifact! artifact)]
        (binding [*out* *err*]
          (println "Artifact written:" path))
        {:content [{:type "text" :text message}]}))
    (throw (ex-info (str "Unknown tool: " tool-name) {:code -32601}))))

;; --------------------------------------------------------------------------- Tool definitions (derived from registry)

(def tool-definitions
  (mapv :tool-def (vals tool-registry)))

;; --------------------------------------------------------------------------- MCP dispatch

(defn handle-initialize [_params]
  {:protocolVersion "2024-11-05"
   :capabilities {:tools {:listChanged false}}
   :serverInfo {:name "miniforge-artifact-server"
                :version "2.0.0"}})

(defn handle-tools-list [_params]
  {:tools tool-definitions})

(defn handle-tools-call [params]
  (let [tool-name (get params "name")
        arguments (get params "arguments" {})]
    (handle-tool-call tool-name arguments)))

(defn dispatch [method params]
  (case method
    "initialize"                  (handle-initialize params)
    "tools/list"                  (handle-tools-list params)
    "tools/call"                  (handle-tools-call params)
    "notifications/initialized"   nil
    "notifications/cancelled"     nil
    (throw (ex-info (str "Method not found: " method) {:code -32601}))))

;; --------------------------------------------------------------------------- Main loop

(binding [*out* *err*]
  (println "miniforge-artifact MCP server started, artifact-dir:" artifact-dir))

(let [reader (java.io.BufferedReader. (java.io.InputStreamReader. System/in "UTF-8"))]
  (loop []
    (when-let [line (.readLine reader)]
      (when-not (str/blank? line)
        (try
          (let [msg (json/parse-string line)
                id (get msg "id")
                method (get msg "method")
                params (get msg "params" {})]
            (if id
              ;; Request — needs response
              (try
                (when-let [result (dispatch method params)]
                  (write-response id result))
                (catch Exception e
                  (let [code (or (:code (ex-data e)) -32603)]
                    (binding [*out* *err*]
                      (println "Error handling" method ":" (ex-message e)))
                    (write-error id code (ex-message e)))))
              ;; Notification — no response
              (try
                (dispatch method params)
                (catch Exception e
                  (binding [*out* *err*]
                    (println "Notification error:" (ex-message e)))))))
          (catch Exception e
            (binding [*out* *err*]
              (println "Parse error:" (ex-message e))))))
      (recur))))
