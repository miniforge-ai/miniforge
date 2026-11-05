;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.templates
  "Judge prompt templates, loaded from the classpath.

   The resource boundary for prompt text: where the templates come from
   and what stands in when the resource is absent. `prompts` builds a
   concrete prompt out of them.

   Layer 0: the resource path
   Layer 1: the loaded template map (over Layer 0)"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private judge-prompt-resource "semantic_analyzer/judge-prompt.edn")

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} judge-templates
  "Judge prompt templates loaded from classpath."
  (delay
    (if-let [url (io/resource judge-prompt-resource)]
      (edn/read-string (slurp url))
      {:system "You are a code reviewer. Return EDN violations or []."
       :user "Rule: {{rule-title}}\n\n{{knowledge-content}}\n\nFile: {{file-path}}\n```\n{{file-content}}\n```"
       :batched-system "You are a code reviewer. Tag each violation with :rule-id. Return EDN violations or []."
       :batched-rule-item "### {{rule-id}} — {{rule-title}}\n{{rule-description}}\n{{knowledge-content}}"
       :batched-user "File: {{file-path}}\n```\n{{file-content}}\n```\n\nRules:\n{{rules-block}}\n\nReturn EDN violations tagged with :rule-id, or []."})))
