;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.interface
  "Public API for LLM-based semantic code analysis."
  (:require
   [ai.miniforge.semantic-analyzer.changed-files :as changed-files]
   [ai.miniforge.semantic-analyzer.core :as core]
   [ai.miniforge.semantic-analyzer.judge :as judge]
   [ai.miniforge.semantic-analyzer.prompts :as prompts]
   [ai.miniforge.semantic-analyzer.selection :as selection]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} build-judge-prompt
  "Build the LLM-as-judge system + user prompts for one rule/file pair.
   Args: rule (policy-pack rule map), file-path (string), file-content (string).
   Returns a map {:system string :user string}."
  prompts/build-judge-prompt)

(def ^{:stratum 0} analyze-file
  "Analyze a single file against one behavioral rule via the LLM.
   Args: llm-client (LLM client from ai.miniforge.llm.interface), complete-fn
   (fn [client request] -> response map), rule (policy-pack rule with
   :rule/knowledge-content), file-path (string), file-content (string).
   Returns a vector of canonical violation maps (empty on no violations or
   unparseable LLM output)."
  judge/analyze-file)

(def ^{:stratum 0} analyze-rule
  "Analyze all matching files in a repo against one behavioral rule.
   Args: llm-client, complete-fn, repo-path (string repo root), rule.
   Returns a map {:rule/id keyword :violations vector :files-analyzed int
   :duration-ms int :status keyword}. Selects files via select-files-for-rule."
  core/analyze-rule)

(def ^{:stratum 0} analyze-rule-on-files
  "Analyze one rule against an explicit set of changed files (changed-files
   scope) instead of scanning the repo.
   Args: llm-client, complete-fn, rule, files (vector of {:path :content}).
   Filters by the rule's globs + size limit, judges per file. Returns the same
   map shape as analyze-rule."
  changed-files/analyze-rule-on-files)

(def ^{:stratum 0} analyze-rules-on-files
  "Batched changed-files judge: ONE LLM call per file across MANY rules.
   Args: llm-client, complete-fn, rules (vector of rule maps), files (vector of
   {:path :content}), optional opts {:deadline-ms int}. Sends the rule pack
   once per file (not per rule×file). A failed or unparseable judge call, and
   every file left when the deadline passes, yields judge-failure violations
   (`:semantic/judge-failure`), never an empty result.
   Returns {:by-rule {rule-id [violation...]} :files-analyzed int :calls int
   :duration-ms int :status :completed|:deadline-exceeded}."
  changed-files/analyze-rules-on-files)

(def ^{:stratum 0} analyze-rules-parallel
  "Analyze many behavioral rules in parallel batches with per-rule timeouts.
   Args: llm-client, complete-fn, repo-path (string), rules (vector of rule
   maps), opts (map with optional :timeout-ms per-rule timeout default 300000
   and :max-parallel max concurrent rules default 4).
   Returns a vector of per-rule result maps (same shape as analyze-rule). On a
   per-rule timeout the result carries :status :timeout (no :error key); on an
   exception it carries :status :error plus an :error message string."
  core/analyze-rules-parallel)

(def ^{:stratum 0} select-files-for-rule
  "Select repo files matching a rule's :rule/applies-to file globs.
   Args: repo-path (string repo root), rule (policy-pack rule map).
   Returns a lazy/clojure sequence of java.io.File, capped at the per-rule file
   limit, excluding files over the max size limit."
  selection/select-files-for-rule)

(def ^{:stratum 0} behavioral-rules
  "Filter rules to those carrying :rule/knowledge-content (need LLM analysis).
   Args: pack-rules (sequence of policy-pack rule maps).
   Returns a vector of the matching rule maps."
  selection/behavioral-rules)
