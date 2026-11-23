;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.judge
  "One judge call against one file: build the prompt, invoke the LLM, parse
   the answer into canonical violations.

   Two modes sit side by side — one rule per call (`analyze-file`) and many
   rules per call (`analyze-file-batched`). Both are the primitives the
   scope namespaces (`changed-files`, `core`) combine.

   A call that fails, or an answer that does not parse, is a judge failure:
   one violation per rule asked about, never an empty result.

   Layer 0: one judge call and its outcome
   Layer 1: the single-rule and batched judge calls (over Layer 0)
   Layer 2: the repo-file variant of the single-rule call (over Layer 1)"
  (:require
   [ai.miniforge.semantic-analyzer.limits :as limits]
   [ai.miniforge.semantic-analyzer.parsing :as parsing]
   [ai.miniforge.semantic-analyzer.prompts :as prompts]
   [ai.miniforge.semantic-analyzer.selection :as selection]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} judge-call
  "Ask the judge once with a built prompt. Returns {:raws [...]} with the
   parsed findings, or {:failure kw :detail str} when the call failed
   (`:success false`) or its answer did not parse."
  [llm-client complete-fn {:keys [system user]}]
  (let [response (complete-fn llm-client {:system   system
                                          :messages [{:role "user" :content user}]})
        content  (str (get response :content ""))
        raws     (parsing/parse-judge-response content)]
    (cond
      (false? (:success response))
      {:failure :call-failed
       :detail  (str (get-in response [:error :message] (:error response)))}

      (nil? raws)
      {:failure :unparseable
       :detail  (subs content 0 (min limits/judge-answer-excerpt-chars (count content)))}

      :else
      {:raws raws})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} analyze-file
  [llm-client complete-fn rule file-path file-content]
  (let [{:keys [raws failure detail]}
        (judge-call llm-client complete-fn
                    (prompts/build-judge-prompt rule file-path file-content))]
    (if failure
      [(parsing/judge-failure-violation rule file-path failure detail)]
      (parsing/judge-response->violations rule file-path raws))))

(defn ^{:stratum 1} analyze-file-batched
  "One judge call: a single file against MANY rules. Returns canonical violation
   maps. A violation tagged with a rule in the batch is attributed to it. A
   violation the model leaves UNPLACEABLE — no `:rule-id`, or one not in the
   batch — is FAIL-CLOSED: attributed to every rule in the batch, so a real
   finding the model forgot (or mis-)tagged is never silently dropped. It blocks
   until fixed, even if over-attributed. (With a single-rule batch this is just
   that rule.) Over-attribution is the safe direction for an acting gate;
   dropping would be a silent enforcement hole. A judge failure likewise
   blocks every rule in the batch."
  [llm-client complete-fn rules file-path file-content]
  (let [by-id (into {} (map (juxt :rule/id identity)) rules)
        {:keys [raws failure detail]}
        (judge-call llm-client complete-fn
                    (prompts/build-batched-prompt rules file-path file-content))]
    (if failure
      (mapv #(parsing/judge-failure-violation % file-path failure detail) rules)
      (vec (mapcat
            (fn [v]
              (if-let [rule (get by-id (parsing/->rule-kw (:rule-id v)))]
                [(parsing/raw->violation rule file-path v)]
                (mapv #(parsing/raw->violation % file-path v) rules)))
            raws)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} analyze-file-in-repo
  "Analyze a single file from the repo against a rule."
  [llm-client complete-fn repo-path rule f]
  (let [rel-path (selection/relativize repo-path f)
        content  (slurp f)]
    (analyze-file llm-client complete-fn rule rel-path content)))
