(ns ai.miniforge.agent.meta-evaluator
  "Meta-evaluator: LLM-powered tool-use quality/relevance judge.

   The meta-evaluator makes a focused LLM call to determine whether a tool-use
   is relevant and appropriate for the current task context. Container sandbox
   handles safety — the meta-evaluator only judges quality/relevance.

   Architecture:
   - evaluate: pure-ish function that makes an LLM call
   - Fail-open on timeout/error (never block the inner agent on infra issues)
   - Budget: ~500 input tokens, ~100 output tokens per call"
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Prompt construction

(def ^:private system-prompt
  "You are a tool-use quality evaluator for a software engineering agent.
Your job: decide whether a tool call is RELEVANT to the current task.
You do NOT judge safety (container sandbox handles that).
You judge: Is this tool-use on-task? Is it productive? Is it wandering?

Respond with ONLY a JSON object, no other text:
{\"decision\": \"allow\" or \"deny\" or \"ask\",
 \"reasoning\": \"one sentence\",
 \"confidence\": 0.0 to 1.0}")

(defn build-eval-prompt
  "Build a focused prompt for the meta-evaluator LLM call.

   Keeps total input under ~500 tokens by truncating tool-input."
  [{:keys [tool-name tool-input task-context phase]}]
  (let [input-str (pr-str tool-input)
        ;; Truncate tool-input to keep prompt small
        input-summary (if (> (count input-str) 300)
                        (str (subs input-str 0 297) "...")
                        input-str)
        task-summary (or task-context "No task context provided")
        phase-str (if phase (name phase) "unknown")]
    (str "Task: " task-summary "\n"
         "Phase: " phase-str "\n"
         "Tool: " tool-name "\n"
         "Input: " input-summary "\n\n"
         "Is this tool-use relevant and productive for the task?")))

;------------------------------------------------------------------------------ Layer 1
;; Response parsing

(defn parse-eval-response
  "Parse the LLM response into a structured decision map.

   Returns {:decision :allow|:deny|:ask :reasoning string :confidence float}
   Falls back to :allow on parse failure."
  [response-text]
  (try
    (let [;; Strip markdown code fences if present
          cleaned (-> response-text
                      str/trim
                      (str/replace #"^```json?\s*" "")
                      (str/replace #"\s*```$" ""))
          parsed (json/parse-string cleaned true)
          decision (case (str/lower-case (str (:decision parsed)))
                    "allow" :allow
                    "deny"  :deny
                    "ask"   :ask
                    :allow)
          confidence (let [c (:confidence parsed)]
                       (if (number? c)
                         (min 1.0 (max 0.0 (double c)))
                         0.5))]
      {:decision decision
       :reasoning (get parsed :reasoning "No reasoning provided")
       :confidence confidence
       :meta-eval? true})
    (catch Exception _e
      ;; Parse failure -> allow (fail-open)
      {:decision :allow
       :reasoning "Meta-eval parse failure (fail-open)"
       :confidence 0.0
       :meta-eval? true})))

;------------------------------------------------------------------------------ Layer 2
;; Core evaluation

(defn evaluate
  "Evaluate a tool-use for quality/relevance using an LLM call.

   Arguments:
     context - Map with :tool-name, :tool-input, :task-context, :phase
     opts    - Map with :llm-client (required, implements LLMClient protocol)

   Returns {:decision :allow|:deny|:ask :reasoning string :confidence float :meta-eval? true}

   Fail-open: returns :allow on any error (timeout, LLM failure, parse error).
   Container sandbox handles safety; meta-evaluator only judges quality."
  [context opts]
  (try
    (let [llm-client (:llm-client opts)
          complete-fn (requiring-resolve 'ai.miniforge.llm.interface.protocols.llm-client/complete*)
          prompt (build-eval-prompt context)
          request {:prompt prompt
                   :system system-prompt
                   :max-tokens 150}
          response (complete-fn llm-client request)]
      (if (:success response)
        (parse-eval-response (:content response))
        ;; LLM call failed -> allow (fail-open)
        {:decision :allow
         :reasoning (str "Meta-eval LLM error (fail-open): "
                         (get-in response [:error :message] "unknown"))
         :confidence 0.0
         :meta-eval? true}))
    (catch Exception e
      ;; Any exception -> allow (fail-open)
      {:decision :allow
       :reasoning (str "Meta-eval exception (fail-open): " (ex-message e))
       :confidence 0.0
       :meta-eval? true})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test prompt construction
  (build-eval-prompt {:tool-name "Bash"
                      :tool-input {:command "ls -la"}
                      :task-context "Implement a REST API endpoint for user registration"
                      :phase :implement})

  ;; Test response parsing
  (parse-eval-response "{\"decision\": \"allow\", \"reasoning\": \"ls is relevant for exploring the project\", \"confidence\": 0.9}")
  ;; => {:decision :allow, :reasoning "ls is relevant...", :confidence 0.9, :meta-eval? true}

  (parse-eval-response "garbage")
  ;; => {:decision :allow, :reasoning "Meta-eval parse failure (fail-open)", :confidence 0.0, :meta-eval? true}

  :end)
