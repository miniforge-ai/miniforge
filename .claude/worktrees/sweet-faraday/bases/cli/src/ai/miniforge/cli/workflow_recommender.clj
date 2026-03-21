(ns ai.miniforge.cli.workflow-recommender
  "LLM-based workflow recommendation system.

   Provides semantic analysis of task specifications to recommend
   the most appropriate workflow, complementing rule-based selection.

   Returns follow the anomaly system pattern - successful recommendations
   are plain maps, failures are anomaly maps with :anomaly/category."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.workflow.interface :as workflow]
   [ai.miniforge.response.interface :as response]))

;;------------------------------------------------------------------------------ Layer 0
;; Prompt templates

(defn build-workflow-summary
  "Build a concise summary of a workflow for LLM prompt.

   Arguments:
     workflow - Workflow definition map

   Returns: String summary"
  [workflow]
  (let [chars (workflow/workflow-characteristics workflow)]
    (str "- " (name (:id chars)) " (v" (:version chars) ")"
         "\n  " (:name chars)
         "\n  Complexity: " (name (:complexity chars))
         "\n  Phases: " (:phases chars)
         "\n  Task types: " (str/join ", " (map name (:task-types chars)))
         (when (:has-review chars) "\n  Includes code review")
         (when (:has-testing chars) "\n  Includes testing"))))

(defn build-workflow-summaries
  "Build summaries for all available workflows.

   Arguments:
     workflows - Sequence of workflow definition maps

   Returns: String with workflow summaries"
  [workflows]
  (str/join "\n\n" (map build-workflow-summary workflows)))

(defn extract-spec-summary
  "Extract key information from spec for LLM analysis.

   Arguments:
     spec - Task specification map

   Returns: String summary"
  [spec]
  (let [title (:spec/title spec)
        description (:spec/description spec)
        intent (:spec/intent spec)
        constraints (:spec/constraints spec)
        workflow-type (:spec/workflow-type spec)]
    (str "Title: " title "\n\n"
         (when description (str "Description: " description "\n\n"))
         (when intent (str "Intent: " (pr-str intent) "\n\n"))
         (when constraints (str "Constraints: " (pr-str constraints) "\n\n"))
         (when workflow-type (str "Preferred workflow: " workflow-type)))))

(defn build-recommendation-prompt
  "Build LLM prompt for workflow recommendation.

   Arguments:
     spec - Task specification map
     workflows - Sequence of available workflows

   Returns: String prompt"
  [spec workflows]
  (str "You are a workflow recommendation system for Miniforge, an agentic SDLC platform.\n\n"
       "Your task is to analyze a task specification and recommend the most appropriate workflow.\n\n"
       "Available workflows:\n\n"
       (build-workflow-summaries workflows)
       "\n\n"
       "Task specification:\n\n"
       (extract-spec-summary spec)
       "\n\n"
       "Analyze the task based on:\n"
       "1. Task complexity (simple, medium, complex)\n"
       "2. Risk level (low, medium, high)\n"
       "3. Review requirements (minimal, standard, comprehensive)\n"
       "4. Time constraints (quick, normal, extensive)\n"
       "5. Task type (feature, bugfix, refactor, test)\n\n"
       "Provide your recommendation as a JSON object with this exact structure:\n"
       "{\n"
       "  \"workflow\": \"workflow-id\",\n"
       "  \"confidence\": 0.85,\n"
       "  \"reasoning\": \"Brief explanation of why this workflow is recommended\",\n"
       "  \"characteristics\": [\"multi-phase\", \"has-review\", \"comprehensive\"]\n"
       "}\n\n"
       "Respond ONLY with the JSON object, no additional text."))

;;------------------------------------------------------------------------------ Layer 1
;; LLM interaction

(defn parse-llm-response
  "Parse JSON response from LLM.

   Arguments:
     response-text - String response from LLM

   Returns: Parsed map or nil"
  [response-text]
  (try
    ;; Try to extract JSON from response (may have markdown code blocks)
    ;; Use (?s) flag so . matches newlines — LLM JSON typically spans multiple lines
    (let [json-text (if (str/includes? response-text "```")
                      (second (re-find #"(?s)```(?:json)?\s*(\{.*?\})\s*```" response-text))
                      response-text)
          parsed (json/parse-string (or json-text response-text) true)]
      ;; Convert workflow string to keyword
      (update parsed :workflow keyword))
    (catch Exception e
      (println "Warning: Failed to parse LLM response:" (ex-message e))
      nil)))

(defn call-llm-for-recommendation
  "Call LLM to get workflow recommendation.

   Arguments:
     llm-client - LLM client (from ai.miniforge.llm.interface)
     prompt - String prompt

   Returns: LLM response map or nil"
  [llm-client prompt]
  (when llm-client
    (try
      (let [complete-fn (requiring-resolve 'ai.miniforge.llm.interface/complete)
            result (complete-fn llm-client {:prompt prompt :max-tokens 500})]
        (when (:success result)
          (:content result)))
      (catch Exception e
        (println "Warning: LLM recommendation failed:" (ex-message e))
        nil))))

;;------------------------------------------------------------------------------ Layer 2
;; Recommendation logic

(defn recommend-workflow
  "Recommend a workflow using LLM semantic analysis.

   Arguments:
     spec - Task specification map
     available-workflows - Sequence of workflow definition maps
     llm-client - Optional LLM client

   Returns: WorkflowRecommendation map (validated against schema) or anomaly map
     Success: {:workflow keyword, :confidence float, :reasoning string, :source :llm, ...}
     Failure: {:anomaly/category keyword, :anomaly/message string, ...}"
  [spec available-workflows llm-client]
  (if-not llm-client
    (response/make-anomaly :anomalies/unavailable
                           "LLM client not available for workflow recommendation"
                           {:operation :recommend-workflow
                            :spec-title (:spec/title spec)})
    (try
      (let [prompt (build-recommendation-prompt spec available-workflows)
            response-text (call-llm-for-recommendation llm-client prompt)]
        (if-not response-text
          (response/make-anomaly :anomalies/unavailable
                                 "LLM did not return a response"
                                 {:operation :recommend-workflow
                                  :spec-title (:spec/title spec)})
          (if-let [parsed (parse-llm-response response-text)]
            (let [recommendation (assoc parsed :source :llm)]
              ;; Validate against schema
              (if (workflow/valid-recommendation? recommendation)
                recommendation
                (response/make-anomaly :anomalies/incorrect
                                       "LLM returned invalid recommendation format"
                                       {:operation :recommend-workflow
                                        :validation-errors (workflow/explain-recommendation recommendation)
                                        :recommendation recommendation})))
            (response/make-anomaly :anomalies/fault
                                   "Failed to parse LLM response"
                                   {:operation :recommend-workflow
                                    :raw-response response-text}))))
      (catch Exception e
        (response/from-exception e)))))

;;------------------------------------------------------------------------------ Layer 3
;; Fallback recommendations

(defn recommend-by-task-type
  "Fallback recommendation based on task type.

   Arguments:
     spec - Task specification map
     available-workflows - Sequence of workflows

   Returns: Workflow recommendation map"
  [spec available-workflows]
  (let [task-type (or (:spec/workflow-type spec) :simple)
        matching-workflows (filter #(some #{task-type} (:workflow/task-types %)) available-workflows)]
    (if (seq matching-workflows)
      {:workflow (:workflow/id (first matching-workflows))
       :confidence 0.5
       :reasoning (str "Fallback: Selected based on task type " task-type)
       :source :fallback}
      {:workflow :simple-test-v1
       :confidence 0.3
       :reasoning "Fallback: Default to simple workflow"
       :source :fallback})))

(defn recommend-workflow-with-fallback
  "Recommend workflow with LLM, falling back to rule-based if needed.

   Arguments:
     spec - Task specification map
     llm-client - Optional LLM client

   Returns: Workflow recommendation map"
  [spec llm-client]
  (workflow/ensure-initialized!)
  (let [available-workflows (workflow/list-workflows)
        llm-recommendation (when llm-client
                             (recommend-workflow spec available-workflows llm-client))]
    (if (and llm-recommendation (:workflow llm-recommendation))
      llm-recommendation
      (recommend-by-task-type spec available-workflows))))
