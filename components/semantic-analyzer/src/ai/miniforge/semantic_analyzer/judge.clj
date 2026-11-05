;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.judge
  "One judge call against one file: build the prompt, invoke the LLM, parse
   the answer into canonical violations.

   Two modes sit side by side — one rule per call (`analyze-file`) and many
   rules per call (`analyze-file-batched`). Both are the primitives the
   scope namespaces (`changed-files`, `core`) combine.

   Layer 0: the single-rule and batched judge calls
   Layer 1: the repo-file variant of the single-rule call (over Layer 0)"
  (:require
   [ai.miniforge.semantic-analyzer.parsing :as parsing]
   [ai.miniforge.semantic-analyzer.prompts :as prompts]
   [ai.miniforge.semantic-analyzer.selection :as selection]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} analyze-file
  [llm-client complete-fn rule file-path file-content]
  (let [{:keys [system user]} (prompts/build-judge-prompt rule file-path file-content)
        response (complete-fn llm-client
                              {:system   system
                               :messages [{:role "user" :content user}]})
        content  (get response :content "")]
    (parsing/judge-response->violations rule file-path
                                        (parsing/parse-judge-response content))))

(defn ^{:stratum 0} analyze-file-batched
  "One judge call: a single file against MANY rules. Returns canonical violation
   maps. A violation tagged with a rule in the batch is attributed to it. A
   violation the model leaves UNPLACEABLE — no `:rule-id`, or one not in the
   batch — is FAIL-CLOSED: attributed to every rule in the batch, so a real
   finding the model forgot (or mis-)tagged is never silently dropped. It blocks
   until fixed, even if over-attributed. (With a single-rule batch this is just
   that rule.) Over-attribution is the safe direction for an acting gate;
   dropping would be a silent enforcement hole."
  [llm-client complete-fn rules file-path file-content]
  (let [by-id    (into {} (map (juxt :rule/id identity)) rules)
        {:keys [system user]} (prompts/build-batched-prompt rules file-path file-content)
        response (complete-fn llm-client {:system system
                                          :messages [{:role "user" :content user}]})
        raws     (parsing/parse-judge-response (get response :content ""))]
    (vec (mapcat
          (fn [v]
            (if-let [rule (get by-id (parsing/->rule-kw (:rule-id v)))]
              [(parsing/raw->violation rule file-path v)]
              (mapv #(parsing/raw->violation % file-path v) rules)))
          raws))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} analyze-file-in-repo
  "Analyze a single file from the repo against a rule."
  [llm-client complete-fn repo-path rule f]
  (let [rel-path (selection/relativize repo-path f)
        content  (slurp f)]
    (analyze-file llm-client complete-fn rule rel-path content)))
