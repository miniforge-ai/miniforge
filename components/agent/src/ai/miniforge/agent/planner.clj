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

(ns ai.miniforge.agent.planner
  "Planner agent implementation.
   Analyzes specifications and creates detailed implementation plans."
  (:require
   [ai.miniforge.agent.artifact-session :as artifact-session]
   [ai.miniforge.agent.budget :as budget]
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.agent.prompts :as prompts]
   [ai.miniforge.agent.result-boundary :as result-boundary]
   [ai.miniforge.agent.role-config :as role-config]
   [ai.miniforge.agent.specialized :as specialized]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.response.interface :as response]
   [malli.core :as m]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Planner-specific schemas

(def PlanTask
  "Schema for a task in the plan."
  [:map
   [:task/id uuid?]
   [:task/description [:string {:min 1}]]
   [:task/type [:enum :implement :test :review :design :deploy :configure]]
   [:task/dependencies {:optional true} [:vector uuid?]]
   [:task/acceptance-criteria {:optional true} [:vector [:string {:min 1}]]]
   [:task/estimated-effort {:optional true} [:enum :small :medium :large :xlarge]]
   [:task/component {:optional true} [:string {:min 1}]]
   [:task/exclusive-files {:optional true} [:vector [:string {:min 1}]]]
   [:task/stratum {:optional true} [:int {:min 0}]]
   ;; Multi-parent merge strategy — only meaningful when
   ;; `:task/dependencies` has 2+ entries. Default `:git-merge` (ort for
   ;; 2 effective parents, octopus for 3+). `:sequential-merge` applies
   ;; parents one-at-a-time as pairwise merges in declaration order.
   ;; See specs/informative/I-DAG-MULTI-PARENT-MERGE.md §4. Single-
   ;; parent and zero-dep tasks ignore this key.
   [:task/merge-strategy {:optional true}
    [:enum :git-merge :sequential-merge]]])

(def Plan
  "Schema for the planner's output."
  [:map
   [:plan/id uuid?]
   [:plan/name [:string {:min 1}]]
   [:plan/tasks [:vector PlanTask]]
   [:plan/estimated-complexity {:optional true} [:enum :low :medium :high]]
   [:plan/risks {:optional true} [:vector [:string {:min 1}]]]
   [:plan/assumptions {:optional true} [:vector [:string {:min 1}]]]
   [:plan/created-at {:optional true} inst?]])

;; System Prompt - loaded from resources/prompts/planner.edn

(def planner-system-prompt
  "System prompt for the planner agent.
   Loaded from EDN resource for configurability."
  (delay (prompts/load-prompt :planner)))

(def ^:private planner-prompt-data
  "Full prompt data map for the planner agent.
   Exposes knobs like :prompt/max-turns that gate backend-CLI loop length."
  (delay (prompts/load-prompt-data :planner)))

;------------------------------------------------------------------------------ Layer 1
;; Planner functions

(defn validate-plan
  "Validate a plan against the Plan schema and check for structural issues."
  [plan]
  (let [schema-valid? (m/validate Plan plan)]
    (if-not schema-valid?
      {:valid? false
       :errors (schema/explain Plan plan)}
      ;; Additional structural validations
      (let [task-ids (set (map :task/id (:plan/tasks plan)))
            ;; Check for invalid dependency references
            invalid-deps (for [task (:plan/tasks plan)
                               dep (:task/dependencies task)
                               :when (not (contains? task-ids dep))]
                           {:task (:task/id task)
                            :invalid-dependency dep})
            ;; Check for circular dependencies (simple check)
            has-self-dep? (some (fn [task]
                                  (some #{(:task/id task)} (:task/dependencies task)))
                                (:plan/tasks plan))]
        (cond
          (seq invalid-deps)
          {:valid? false
           :errors {:dependencies (str "Invalid dependency references: " (pr-str invalid-deps))}}

          has-self-dep?
          {:valid? false
           :errors {:dependencies "Task has circular self-dependency"}}

          :else
          {:valid? true :errors nil})))))

;; NOTE: Helper function for commented-out generate-plan function
#_(defn make-task
  "Helper to create a task with generated ID."
  [{:keys [description type dependencies acceptance-criteria estimated-effort]
    :or {dependencies []
         acceptance-criteria []
         estimated-effort :medium}}]
  (cond-> {:task/id (random-uuid)
           :task/description description
           :task/type type}
    (seq dependencies) (assoc :task/dependencies dependencies)
    (seq acceptance-criteria) (assoc :task/acceptance-criteria acceptance-criteria)
    estimated-effort (assoc :task/estimated-effort estimated-effort)))

(defn format-existing-files
  "Format existing file contents for inclusion in the user prompt.

   Arguments:
   - files - Vector of {:path :content :truncated? :lines} maps

   Returns:
   - Formatted markdown string, or nil if no files"
  [files]
  (when (seq files)
    (->> files
         (map (fn [{:keys [path content truncated?]}]
                (str "\n### " path
                     (when truncated? " (truncated)")
                     "\n```\n" content "\n```")))
         (str/join "\n"))))

(defn- existing-files->cache-map
  "Build the artifact-session context cache map from existing files."
  [existing-files]
  (into {}
        (map (fn [{:keys [path content]}]
               [path content]))
        existing-files))

(defn- render-template
  "Render a `{{key}}`-style template with the given substitutions.
   Each substitutions map entry `{kw v}` replaces the literal string
   \"{{kw}}\" with `v` (nil values render as the empty string)."
  [template substitutions]
  (reduce-kv (fn [text k v]
               (str/replace text (str "{{" (name k) "}}") (or v "")))
             (or template "")
             substitutions))

(defn- existing-files-section
  "Build the planner-prompt section that previews existing in-scope
   files. Returns the empty string when no files are present."
  [existing-files]
  (if (seq existing-files)
    (render-template (get @planner-prompt-data :prompt/existing-files-template)
                     {:files-list (format-existing-files existing-files)})
    ""))

(defn- build-user-prompt
  "Render the planner user-turn prompt from the spec text and any
   existing in-scope files. Template lives in
   resources/prompts/planner.edn (:prompt/user-template)."
  [spec-text existing-files]
  (render-template (get @planner-prompt-data :prompt/user-template)
                   {:spec-text              spec-text
                    :existing-files-section (existing-files-section existing-files)}))

(defn spec->text
  "Convert a spec to text for the LLM."
  [spec]
  (if (map? spec)
    (or (:description spec)
        (:spec/content spec)
        (:content spec)
        (pr-str spec))
    (str spec)))

(defn parse-plan-response
  "Parse the LLM response to extract a plan.
   Handles both EDN in code blocks and plain EDN.
   Returns nil if the parsed result is not a map."
  [response-content]
  (try
    (let [parsed (if-let [match (re-find #"```(?:clojure|edn)?\s*\n([\s\S]*?)\n```" response-content)]
                   (edn/read-string (second match))
                   ;; Try to parse the whole response as EDN
                   (edn/read-string response-content))]
      ;; Validate that the parsed result is a map (plan should be a map)
      (when (map? parsed)
        parsed))
    (catch Exception _
      ;; Return nil if parsing fails
      nil)))

(defn- planner-response-content
  "Return the best available content payload for plan extraction."
  [llm-response]
  (or (llm/get-content llm-response)
      (get (llm/get-error llm-response) :stdout)
      ""))

(defn- submission-retry-prompt
  "Build a short, submission-only retry prompt for planner recovery.
   Template lives in resources/prompts/planner.edn
   (:prompt/submission-retry-template)."
  [spec-text prior-content]
  (render-template (get @planner-prompt-data :prompt/submission-retry-template)
                   {:spec-text     spec-text
                    :prior-content prior-content}))

(defn- planner-submission-retry?
  "True when a failed planner turn produced useful prose but no submitted plan."
  [llm-response submitted-plan parsed-plan]
  (let [err (llm/get-error llm-response)
        stdout (:stdout err)
        err-type (:type err)]
    (and (nil? submitted-plan)
         (nil? parsed-plan)
         (not (llm/success? llm-response))
         (seq stdout)
         (#{"adaptive_timeout" "cli_error"} err-type))))

;; make-fallback-plan removed — silent fallback masks real failures.
;; Plan generation now throws with evidence on failure (see invoke-fn below).

;; NOTE: This function is currently unused but kept as reference for future implementation
;; where plan generation may be delegated to a separate function rather than done inline.
#_(defn generate-plan
  "Generate a plan from analyzed specification.
   In a real implementation, this would use an LLM with the system prompt."
  [analysis context]
  ;; Generate a basic plan structure
  ;; In production, this would be LLM-generated based on the system prompt
  (let [{:keys [spec-text estimated-complexity has-tests?]} analysis
        plan-id (random-uuid)
        base-name (or (:plan-name context)
                      (str "plan-" (subs (str plan-id) 0 8)))
        design-task (make-task
                     {:description (str "Design solution for: " (subs spec-text 0 (min 100 (count spec-text))))
                      :type :design
                      :acceptance-criteria ["Design document created"
                                            "Approach reviewed and approved"]
                      :estimated-effort :medium})
        impl-task (make-task
                   {:description "Implement the designed solution"
                    :type :implement
                    :dependencies [(:task/id design-task)]
                    :acceptance-criteria ["Code compiles without errors"
                                          "Follows project conventions"
                                          "No linter warnings"]
                    :estimated-effort (case estimated-complexity
                                        :low :small
                                        :medium :medium
                                        :high :large)})
        test-task (make-task
                   {:description "Write tests for the implementation"
                    :type :test
                    :dependencies [(:task/id impl-task)]
                    :acceptance-criteria ["Unit tests pass"
                                          "Edge cases covered"
                                          "Test coverage meets threshold"]
                    :estimated-effort :medium})
        review-task (make-task
                     {:description "Code review checkpoint"
                      :type :review
                      :dependencies [(:task/id test-task)]
                      :acceptance-criteria ["Code reviewed"
                                            "All comments addressed"]
                      :estimated-effort :small})]
    {:plan/id plan-id
     :plan/name base-name
     :plan/tasks [design-task impl-task test-task review-task]
     :plan/estimated-complexity estimated-complexity
     :plan/risks (cond-> []
                   (= :high estimated-complexity)
                   (conj "High complexity may require iteration")

                   (not has-tests?)
                   (conj "No existing test infrastructure"))
     :plan/assumptions ["Requirements are complete and stable"
                        "Dependencies are available"]
     :plan/created-at (java.util.Date.)}))

(defn repair-plan
  "Attempt to repair a plan based on validation errors."
  [plan errors _context]
  ;; Simple repair strategies based on common errors
  (let [repaired (atom plan)]
    ;; Fix missing required fields
    (when-not (:plan/id @repaired)
      (swap! repaired assoc :plan/id (random-uuid)))

    (when-not (:plan/name @repaired)
      (swap! repaired assoc :plan/name "unnamed-plan"))

    (when-not (:plan/tasks @repaired)
      (swap! repaired assoc :plan/tasks []))

    ;; Fix tasks without IDs
    (swap! repaired update :plan/tasks
           (fn [tasks]
             (mapv (fn [task]
                     (cond-> task
                       (not (:task/id task))
                       (assoc :task/id (random-uuid))

                       (not (:task/description task))
                       (assoc :task/description "Task description needed")

                       (not (:task/type task))
                       (assoc :task/type :implement)))
                   tasks)))

    ;; Remove invalid dependencies
    (let [valid-ids (set (map :task/id (:plan/tasks @repaired)))]
      (swap! repaired update :plan/tasks
             (fn [tasks]
               (mapv (fn [task]
                       (if-let [deps (:task/dependencies task)]
                         (assoc task :task/dependencies
                                (vec (filter valid-ids deps)))
                         task))
                     tasks))))

    {:status :success
     :output @repaired
     :repairs-made (when (not= plan @repaired)
                     {:original-errors errors})}))

(defn ensure-task-ids
  "Ensure all tasks in a plan have proper UUIDs."
  [tasks]
  (mapv (fn [t] (update t :task/id #(or % (random-uuid)))) tasks))

(defn finalize-plan
  "Ensure a plan has proper ID, task IDs, and timestamp."
  [plan]
  (-> plan
      (update :plan/id #(or % (random-uuid)))
      (update :plan/tasks ensure-task-ids)
      (assoc :plan/created-at (java.util.Date.))))

(defn validate-already-satisfied
  "Check that an already-satisfied claim is backed by evidence.
   Returns {:valid? bool :reason string}.

   Rejects when:
   - No evidence provided
   - Evidence vector is empty
   - Acceptance criteria reference specific function names that
     don't appear in any evidence :proof or :satisfied-by fields"
  [plan acceptance-criteria]
  (let [evidence (get plan :plan/evidence [])]
    (cond
      (empty? evidence)
      {:valid? false :reason "No evidence provided for already-satisfied claim"}

      (and (seq acceptance-criteria)
           (let [proofs (str/join " " (map #(str (:proof %) " " (:satisfied-by %)) evidence))
                 ;; Extract function-like names from acceptance criteria
                 fn-names (->> acceptance-criteria
                               (mapcat #(re-seq #"`([a-z][a-z0-9-]*!?)`" %))
                               (map second)
                               (remove #{"true" "false" "nil"})
                               seq)]
             (when fn-names
               (some #(not (str/includes? proofs %)) fn-names))))
      {:valid? false :reason "Acceptance criteria reference functions not found in evidence"}

      :else
      {:valid? true})))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(def ^:private planner-disallowed-tools
  "Native Claude Code tools the planner MUST NOT call.

   Rationale (iteration 5 + 6 dogfood evidence): the planner had access
   to Read/Bash/Grep/Glob/Agent and used them exclusively — 140+ tool
   calls across two iterations, zero plan artifacts produced. The MCP
   context cache (context_read/context_grep/context_glob) — which the
   explore phase pre-populates with the scoped files the planner
   actually needs — was consulted once in 140 tool calls.

   Forcing the MCP path achieves two things:
     1. The planner works off a curated, pre-loaded file set instead
        of flailing through the whole filesystem. Fewer noisy reads,
        faster convergence.
     2. Cache-miss events land in the session :context-misses file,
        so we learn what the planner actually wanted that the explore
        phase didn't provide. Signal the context-quality theme needs.

   Write stays allowed — that's the container-promotion submission
   path (.miniforge/plan.edn, landed in GROUP 1). WebSearch/WebFetch
   are NOT in this list because disallowing them without a cached MCP
   replacement would regress capability; they ship with GROUP 2B /
   2c of the planner-convergence spec.

   Matches the role-scoped disallow-list pattern tester.clj uses."
  ["Read" "Bash" "Grep" "Glob" "Agent" "LS"])

(defn- create-planner-progress-monitor
  "Planner main-turn progress monitor. Thresholds live in
   resources/prompts/planner.edn (:prompt/progress-monitor)."
  []
  (prompts/load-progress-monitor @planner-prompt-data
                                 :prompt/progress-monitor))

(defn- invoke-planner-session
  "Session body for the planner: build mcp-opts with model hint, call LLM."
  [session llm-client user-prompt config context on-chunk existing-files]
  (when (seq existing-files)
    (artifact-session/write-context-cache-for-session!
     session
     (existing-files->cache-map existing-files)))
  (let [budget-usd (budget/resolve-cost-budget-usd :planner config context)
        ;; Turn cap: read from prompt EDN (:prompt/max-turns) with a safe
        ;; fallback. 80 was observed empirically as the smallest value that
        ;; doesn't cut Opus off mid-exploration on event-log-tool-visibility-
        ;; sized specs (2026-04-18 dogfood). OPSV-converged target — see
        ;; work/n07-opsv-agent-budgets.spec.edn.
        max-turns (get @planner-prompt-data :prompt/max-turns 80)
        ;; Thread the session workdir into the LLM request so the Claude
        ;; subprocess is spawned inside the task worktree. Without this,
        ;; Write(".miniforge/plan.edn") resolves to the miniforge repo
        ;; root — observed iter 17, where Claude Code then refused to
        ;; overwrite a stale plan.edn left over from an earlier run:
        ;;   <tool_use_error>File has not been read yet. Read it first
        ;;    before writing to it.</tool_use_error>
        ;; The implementer threads :workdir the same way.
        mcp-opts (cond-> (artifact-session/session->mcp-opts session budget-usd max-turns)
                   true (assoc :model (model/default-model-for-role :planner)
                               :disallowed-tools planner-disallowed-tools
                               :progress-monitor (create-planner-progress-monitor))
                   (:workdir session) (assoc :workdir (:workdir session)))]
    (if on-chunk
      (llm/chat-stream llm-client user-prompt on-chunk
                       (merge {:system @planner-system-prompt} mcp-opts))
      (llm/chat llm-client user-prompt
                (merge {:system @planner-system-prompt} mcp-opts)))))

(defn- normalize-planner-result
  [response worktree-artifacts artifact]
  (result-boundary/normalize-llm-result
   {:role :plan
    :response response
    :worktree-artifacts worktree-artifacts
    :artifact artifact
    :content-fn planner-response-content
    :parse-response parse-plan-response}))

(defn- planner-log-data
  "Build planner invocation telemetry data."
  [llm-response on-chunk normalized]
  (let [plan-source (case (:artifact-source normalized)
                      :worktree-metadata :worktree
                      :mcp :mcp-artifact
                      :final-message)]
    (cond-> {:success (llm/success? llm-response)
             :tokens (get llm-response :tokens 0)
             :streaming? (boolean on-chunk)
             :plan-source plan-source}
      (:stop-reason llm-response)
      (assoc :stop-reason (:stop-reason llm-response))

      (:num-turns llm-response)
      (assoc :num-turns (:num-turns llm-response)))))

(defn- invoke-planner-submission-retry-session
  "Run one short follow-up turn that only submits the final plan.
   Turn cap and progress-monitor thresholds come from
   resources/prompts/planner.edn (:prompt/submission-retry-max-turns,
   :prompt/submission-retry-monitor)."
  [session llm-client retry-prompt config context on-chunk]
  (let [prompt-data    @planner-prompt-data
        budget-usd     (budget/resolve-cost-budget-usd :planner config context)
        retry-max-turns (get prompt-data :prompt/submission-retry-max-turns)
        retry-monitor  (prompts/load-progress-monitor
                        prompt-data :prompt/submission-retry-monitor)
        mcp-opts       (cond-> (artifact-session/session->mcp-opts session budget-usd retry-max-turns)
                         true (assoc :model (model/default-model-for-role :planner)
                                     :disallowed-tools planner-disallowed-tools
                                     :progress-monitor retry-monitor)
                         (:workdir session) (assoc :workdir (:workdir session)))]
    (if on-chunk
      (llm/chat-stream llm-client retry-prompt on-chunk
                       (merge {:system @planner-system-prompt} mcp-opts))
      (llm/chat llm-client retry-prompt
                (merge {:system @planner-system-prompt} mcp-opts)))))

(defn- recover-submitted-plan
  "Retry planner submission once when analysis exists but the final submission
   did not land. Returns {:llm-response ... :submitted-plan ... :parsed-plan ...}."
  [llm-client spec-text config context on-chunk prior-content]
  (let [retry-prompt   (submission-retry-prompt spec-text prior-content)
        run-retry      #(invoke-planner-submission-retry-session
                          % llm-client retry-prompt config context on-chunk)
        {:keys [llm-result artifact worktree-artifacts]}
        (artifact-session/with-session context run-retry)
        normalized     (normalize-planner-result llm-result worktree-artifacts artifact)
        submitted-plan (:structured-artifact normalized)
        parsed-plan    (when-not submitted-plan
                         (:parsed-content normalized))]
    {:llm-response   llm-result
     :normalized     normalized
     :submitted-plan submitted-plan
     :parsed-plan    parsed-plan}))

(defn create-planner
  "Create a Planner agent with optional configuration overrides.

   Options:
   - :config - Agent configuration (model, temperature, etc.)
   - :logger - Logger instance
   - :llm-backend - LLM client (if not provided, uses :llm-backend from context)

   Example:
     (create-planner)
     (create-planner {:config {:model \"claude-opus-4-6\"}})"
  [& [opts]]
  (let [logger (or (:logger opts)
                   (log/create-logger {:min-level :info :output (fn [_])}))
        config (->> (merge (role-config/agent-llm-default :planner)
                           (:config opts))
                    (model/apply-default-model :planner)
                    (budget/apply-default-budget :planner))]
    (specialized/create-base-agent
     {:role :planner
      :system-prompt @planner-system-prompt
      :config config
      :logger logger

      :invoke-fn
      (fn [context input]
        (let [llm-client (model/resolve-llm-client-for-role
                          :planner (get opts :llm-backend (:llm-backend context)))
              on-chunk (:on-chunk context)
              spec-text (spec->text input)
              existing-files (:task/existing-files input)
              user-prompt    (build-user-prompt spec-text existing-files)]
          (if llm-client
            ;; Use the real LLM with artifact session for MCP tool support
            (let [{:keys [llm-result artifact worktree-artifacts context-misses]}
                  (artifact-session/with-session context
                    #(invoke-planner-session % llm-client user-prompt config context
                                             on-chunk existing-files))
                  llm-response llm-result
                  normalized (normalize-planner-result llm-response worktree-artifacts artifact)
                  submitted-plan (:structured-artifact normalized)
                  response-content (:content normalized)
                  parsed-plan (when-not submitted-plan
                                (:parsed-content normalized))
                  retry-result (when (planner-submission-retry? llm-response
                                                                submitted-plan
                                                                parsed-plan)
                                 (log/info logger :planner :planner/submission-retry
                                           {:data {:reason :missing-plan-submission
                                                   :content-length (count response-content)}})
                                 (recover-submitted-plan llm-client spec-text
                                                         config context on-chunk
                                                         response-content))
                  final-llm-response   (or (:llm-response retry-result) llm-response)
                  final-normalized     (or (:normalized retry-result) normalized)
                  final-submitted-plan (or (:submitted-plan retry-result) submitted-plan)
                  final-parsed-plan    (or (:parsed-plan retry-result) parsed-plan)
                  retry-tokens         (if (identical? final-llm-response llm-response)
                                         0
                                         (get final-llm-response :tokens 0))
                  tokens               (+ (get llm-response :tokens 0) retry-tokens)]
              (when (seq context-misses)
                (log/info logger :planner :planner/context-cache-misses
                          {:data {:miss-count (count context-misses)
                                  :misses context-misses}}))
              (log/info logger :planner :planner/llm-called
                        {:data (planner-log-data final-llm-response on-chunk final-normalized)})
              ;; Container-promotion preempts CLI error classification:
              ;; if the agent wrote a valid plan.edn into the worktree,
              ;; or submitted a plan through the MCP artifact path,
              ;; that IS the submission and we honor it regardless of
              ;; whether Claude CLI emitted a clean result event
              ;; afterwards. Iter 18 hit a stream-idle timeout AFTER a
              ;; successful Write — the plan existed, but the old
              ;; success-branch-only logic ignored it because the LLM
              ;; response was classified as failure.
              (if (result-boundary/usable-content? final-normalized)
                (let [content (planner-response-content final-llm-response)
                      stop-reason (:stop-reason final-llm-response)
                      num-turns   (:num-turns final-llm-response)
                      plan (or final-submitted-plan
                               final-parsed-plan
                               (response/throw-anomaly! :anomalies.agent/invoke-failed
                                                       "Plan generation failed: EDN parse did not succeed"
                                                       (cond-> {:phase :plan
                                                                :parse-result nil
                                                                :llm-content-length (count content)
                                                                :llm-content-preview (subs content 0 (min 500 (count content)))}
                                                         stop-reason (assoc :stop-reason stop-reason)
                                                         num-turns   (assoc :num-turns num-turns))))
                      plan-final (finalize-plan plan)]
                  ;; Check for already-satisfied response
                  (if (= :already-satisfied (:plan/status plan-final))
                    (let [criteria (get input :spec/acceptance-criteria
                                       (get input :acceptance-criteria []))
                          validation (validate-already-satisfied plan-final criteria)]
                      (if (:valid? validation)
                        (let [output {:plan/id (random-uuid)
                                      :plan/name "already-satisfied"
                                      :plan/tasks []
                                      :plan/summary (:plan/summary plan-final)
                                      :plan/evidence (get plan-final :plan/evidence [])}]
                          (assoc (response/success output {:tokens tokens})
                                 :status :already-satisfied))
                        ;; Reject false already-satisfied — force planning
                        (do
                          (log/info logger :planner :planner/already-satisfied-rejected
                                    {:data {:reason (:reason validation)}})
                          (response/error (str "Already-satisfied claim rejected: "
                                               (:reason validation))))))
                    (response/success plan-final
                                      {:tokens tokens
                                       :metrics (cond-> {:tasks-created (count (:plan/tasks plan-final))
                                                         :complexity (:plan/estimated-complexity plan-final)
                                                         :tokens tokens}
                                                  stop-reason (assoc :stop-reason stop-reason)
                                                  num-turns   (assoc :num-turns num-turns))})))
                ;; LLM call failed — preserve the full llm-error shape
                ;; into :data so the phase-completed event carries
                ;; :type (e.g. "cli_error" / "adaptive_timeout"),
                ;; :stderr / :stdout / :timeout / :exit-code for
                ;; post-mortem. Iters 11-12 lost this context and
                ;; produced undiagnosable \"Unknown error\" / bare
                ;; \"Process timed out\" phase errors.
                (result-boundary/error-response final-normalized "LLM call failed")))
               ;; No LLM client — hard failure
            (response/throw-anomaly! :anomalies.agent/llm-error
                                    "No LLM backend provided for planner agent"
                                    {:phase :plan}))))

      :validate-fn validate-plan

      :repair-fn repair-plan})))

(defn plan-summary
  "Get a summary of a plan for logging/display."
  [plan]
  {:id (:plan/id plan)
   :name (:plan/name plan)
   :task-count (count (:plan/tasks plan))
   :complexity (:plan/estimated-complexity plan)
   :risk-count (count (:plan/risks plan))})

(defn task-dependency-order
  "Return tasks in dependency order (topological sort).
   Tasks with no dependencies come first."
  [plan]
  (let [tasks (:plan/tasks plan)
        task-map (into {} (map (juxt :task/id identity) tasks))
        deps-map (into {} (map (fn [t] [(:task/id t) (set (:task/dependencies t []))]) tasks))]
    ;; Simple topological sort using Kahn's algorithm
    (loop [remaining (set (keys task-map))
           satisfied #{}
           result []]
      (if (empty? remaining)
        result
        (let [ready (filter (fn [id]
                              (every? satisfied (get deps-map id #{})))
                            remaining)]
          (if (empty? ready)
            ;; Cycle detected, return what we have
            (into result (map task-map remaining))
            (recur (apply disj remaining ready)
                   (into satisfied ready)
                   (into result (map task-map ready)))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a planner
  (def planner (create-planner))

  ;; Invoke with a specification
  (specialized/invoke planner
               {:codebase {:has-tests? true}}
               "As a user, I want to log in with my email so that I can access my account.")
  ;; => {:status :success, :output {:plan/id ..., :plan/tasks [...], ...}}

  ;; Validate a plan
  (validate-plan {:plan/id (random-uuid)
                  :plan/name "test-plan"
                  :plan/tasks []})
  ;; => {:valid? true, :errors nil}

  ;; Get task order
  (let [id-a (random-uuid)
        id-b (random-uuid)]
    (task-dependency-order
     {:plan/tasks [{:task/id id-a :task/description "A" :task/type :implement}
                   {:task/id id-b :task/description "B" :task/type :test
                    :task/dependencies [id-a]}]}))

  :leave-this-here)
