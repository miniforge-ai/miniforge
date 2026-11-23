;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.prompts
  "Prompt construction: a rule (or a batch of rules) plus one file's
   content interpolated into the `templates` prompt text.

   Layer 0: interpolation bindings, the batched rules block
   Layer 1: the single-rule and batched system/user prompt pairs
     (over Layer 0)"
  (:require
   [ai.miniforge.policy-pack.interface :as pt]
   [ai.miniforge.semantic-analyzer.templates :as templates]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} rule->prompt-bindings
  "Extract prompt interpolation bindings from a rule and file."
  [rule file-path file-content]
  {:rule-title        (get rule :rule/title "")
   :rule-description  (get rule :rule/description "")
   :knowledge-content (get rule :rule/knowledge-content "")
   :file-path         file-path
   :file-content      file-content})

(defn- ^{:stratum 0} batched-rules-block
  "Render each rule as a `### <id>` heading block by interpolating the
   `:batched-rule-item` prompt template, joined. (The heading markup lives in the
   template, not inline here; `templates/judge-templates` carries a defensive
   fallback for when the resource is absent.)"
  [rules]
  (let [item (get @templates/judge-templates :batched-rule-item "")]
    (str/join "\n\n"
              (map (fn [r] (pt/interpolate
                            item
                            {:rule-id           (str (:rule/id r))
                             :rule-title        (get r :rule/title "")
                             :rule-description  (get r :rule/description "")
                             :knowledge-content (get r :rule/knowledge-content "")}))
                   rules))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build-judge-prompt
  [rule file-path file-content]
  (let [tpl      @templates/judge-templates
        bindings (rule->prompt-bindings rule file-path file-content)]
    {:system (pt/interpolate (get tpl :system) bindings)
     :user   (pt/interpolate (get tpl :user) bindings)}))

(defn ^{:stratum 1} build-batched-prompt
  "Build the batched system + user prompts by interpolating the
   `:batched-system` / `:batched-user` prompt templates from
   `templates/judge-templates`."
  [rules file-path file-content]
  (let [tpl @templates/judge-templates]
    {:system (get tpl :batched-system "")
     :user   (pt/interpolate (get tpl :batched-user "")
                             {:file-path    file-path
                              :file-content file-content
                              :rules-block  (batched-rules-block rules)})}))
