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
   [:task/stratum {:optional true} [:int {:min 0}]]])

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

(defn- invoke-planner-session
  "Session body for the planner: build mcp-opts with model hint, call LLM."
  [session llm-client user-prompt config context on-chunk]
  (let [budget-usd (budget/resolve-cost-budget-usd :planner config context)
        mcp-opts (assoc (artifact-session/session->mcp-opts session budget-usd 40)
                        :model (model/default-model-for-role :planner))]
    (if on-chunk
      (llm/chat-stream llm-client user-prompt on-chunk
                       (merge {:system @planner-system-prompt} mcp-opts))
      (llm/chat llm-client user-prompt
                (merge {:system @planner-system-prompt} mcp-opts)))))

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
        config (->> (merge {:temperature 0.3
                            :max-tokens 4000}
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
              user-prompt (str "Create an implementation plan for the following specification:\n\n"
                               spec-text
                               (when (seq existing-files)
                                 (str "\n\n## Existing Files in Scope\n\n"
                                      "Review these files before planning. If the spec is already "
                                      "fully satisfied by existing code, respond with an evidence bundle:\n"
                                      "```clojure\n{:plan/status :already-satisfied\n"
                                      " :plan/summary \"Brief explanation\"\n"
                                      " :plan/evidence [{:requirement \"what spec requires\"\n"
                                      "                  :satisfied-by \"path/to/file.clj\"\n"
                                      "                  :proof \"specific function/test that satisfies it\"}]}\n```\n"
                                      (format-existing-files existing-files)))
                               "\n\nOutput your plan as a Clojure map following the format in your system prompt. "
                               "Use (random-uuid) for all IDs - just write #uuid \"<any-uuid>\" placeholders that I'll fill in.")]
          (if llm-client
            ;; Use the real LLM with artifact session for MCP tool support
            (let [{:keys [llm-result artifact]}
                  (artifact-session/with-session context
                    #(invoke-planner-session % llm-client user-prompt config context on-chunk))
                  llm-response llm-result
                  tokens (get llm-response :tokens 0)]
              (log/info logger :planner :planner/llm-called
                        {:data {:success (llm/success? llm-response)
                                :tokens tokens
                                :streaming? (boolean on-chunk)}})
              (if (llm/success? llm-response)
                (let [content (llm/get-content llm-response)
                      plan (or artifact
                               (parse-plan-response content)
                               (response/throw-anomaly! :anomalies.agent/invoke-failed
                                                       "Plan generation failed: EDN parse did not succeed"
                                                       {:phase :plan
                                                        :parse-result nil
                                                        :llm-content-length (count content)
                                                        :llm-content-preview (subs content 0 (min 500 (count content)))})))
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
                                       :metrics {:tasks-created (count (:plan/tasks plan-final))
                                                 :complexity (:plan/estimated-complexity plan-final)
                                                 :tokens tokens}})))
                ;; LLM call failed — no silent fallback
                (let [error-msg (or (:message (llm/get-error llm-response))
                                    "LLM call failed")]
                  (response/error error-msg))))
               ;; No LLM client — hard failure
            (response/throw-anomaly! :anomalies.agent/llm-error
                                    "No LLM backend provided for planner agent"
                                    {:phase :plan})))))

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
