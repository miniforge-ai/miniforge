(ns ai.miniforge.mcp-artifact-server.tools
  "Tool definitions and handlers for MCP artifact submission.

   Configuration-driven: tool definitions are loaded from
   `mcp-artifact-server/tool-registry.edn` on the classpath. Builder functions
   are registered separately via `register-builder!`, enabling extensibility
   without modifying the config file."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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
;; Keyword validators — referenced by keyword in EDN config

(def validators
  "Keyword → predicate map for validation rules in the registry config.
   These are the only functions that bridge EDN config to runtime behavior."
  {:non-empty (fn [v] (and v (seq v)))
   :non-blank (fn [v] (and (string? v) (not (str/blank? v))))})

(defn resolve-validator
  "Resolve a keyword validator to its predicate function."
  [check-kw]
  (or (get validators check-kw)
      (throw (ex-info (str "Unknown validator: " check-kw)
                      {:validator check-kw
                       :available (keys validators)}))))

;------------------------------------------------------------------------------ Layer 0
;; Validation

(defn validate-required-params
  "Validate required params against rules. Throws ex-info with code -32602 on failure.
   Accepts keyword validators (from EDN config) or function validators."
  [required-params params]
  (doseq [[param-name {:keys [check msg]}] required-params]
    (let [check-fn (if (keyword? check) (resolve-validator check) check)
          v (get params param-name)]
      (when-not (check-fn v)
        (throw (ex-info msg {:code -32602}))))))

;------------------------------------------------------------------------------ Layer 0
;; Param extractors

(defn non-blank
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

(defn build-plan-task
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

(defn parse-test-files
  "Parse test file params into file maps."
  [files]
  (mapv (fn [f] {:path (get f "path") :content (get f "content")}) files))

(defn aggregate-test-content
  "Aggregate all test file content into a single string."
  [files]
  (str/join "\n" (map #(get % "content" "") files)))

(defn parse-coverage
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
;; Extensible builder registry (atom — builders register at startup)

(defonce ^:private builder-registry*
  (atom {:submit-code-artifact    build-code-artifact
         :submit-plan             build-plan-artifact
         :submit-test-artifact    build-test-artifact
         :submit-release-artifact build-release-artifact}))

(defn register-builder!
  "Register an artifact builder function for a tool.
   builder-key is a keyword matching the :builder field in tool-registry.edn."
  [builder-key builder-fn]
  (swap! builder-registry* assoc builder-key builder-fn))

(defn resolve-builder
  "Resolve a builder keyword to its function."
  [builder-key]
  (or (get @builder-registry* builder-key)
      (throw (ex-info (str "No builder registered for: " builder-key)
                      {:builder builder-key
                       :registered (keys @builder-registry*)}))))

;------------------------------------------------------------------------------ Layer 1
;; Configuration loading

(defn load-registry-config
  "Load tool registry from classpath EDN configuration."
  []
  (if-let [resource (io/resource "mcp-artifact-server/tool-registry.edn")]
    (edn/read-string (slurp resource))
    (throw (ex-info "Tool registry config not found on classpath"
                    {:resource "mcp-artifact-server/tool-registry.edn"}))))

(defonce ^:private registry-config* (atom nil))

(defn ensure-registry-loaded
  "Ensure the registry config is loaded (once)."
  []
  (when-not @registry-config*
    (reset! registry-config* (load-registry-config)))
  @registry-config*)

(defn tool-registry
  "Return the current tool registry (loads from config on first call)."
  []
  (ensure-registry-loaded))

(defn register-tool!
  "Register a new tool at runtime. Merges into the loaded registry.
   tool-name is a string, tool-config is a map with :tool-def, :required-params, :builder."
  [tool-name tool-config]
  (ensure-registry-loaded)
  (swap! registry-config* assoc tool-name tool-config))

;------------------------------------------------------------------------------ Layer 2
;; Tool definitions list (derived from registry)

(defn tool-definitions
  "MCP tool definitions derived from the registry."
  []
  (mapv :tool-def (vals (tool-registry))))

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
  (let [registry (tool-registry)
        {:keys [required-params builder]} (get registry tool-name)]
    (when-not builder
      (throw (ex-info (str "Unknown tool: " tool-name) {:code -32601})))
    (validate-required-params required-params arguments)
    (let [build-fn (resolve-builder builder)
          {:keys [artifact message]} (build-fn arguments)
          path (write-artifact-fn artifact)]
      (binding [*out* *err*]
        (println "Artifact written:" path))
      {:content [{:type "text" :text message}]})))
