;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.semantic-analyzer.interface
  "Public API for LLM-based semantic code analysis."
  (:require
   [ai.miniforge.semantic-analyzer.core :as core]))

(def build-judge-prompt
  "Build the LLM-as-judge system + user prompts for one rule/file pair.
   Args: rule (policy-pack rule map), file-path (string), file-content (string).
   Returns a map {:system string :user string}."
  core/build-judge-prompt)

(def analyze-file
  "Analyze a single file against one behavioral rule via the LLM.
   Args: llm-client (LLM client from ai.miniforge.llm.interface), complete-fn
   (fn [client request] -> response map), rule (policy-pack rule with
   :rule/knowledge-content), file-path (string), file-content (string).
   Returns a vector of canonical violation maps (empty on no violations or
   unparseable LLM output)."
  core/analyze-file)

(def analyze-rule
  "Analyze all matching files in a repo against one behavioral rule.
   Args: llm-client, complete-fn, repo-path (string repo root), rule.
   Returns a map {:rule/id keyword :violations vector :files-analyzed int
   :duration-ms int :status keyword}. Selects files via select-files-for-rule."
  core/analyze-rule)

(def analyze-rules-parallel
  "Analyze many behavioral rules in parallel batches with per-rule timeouts.
   Args: llm-client, complete-fn, repo-path (string), rules (vector of rule
   maps), opts (map with optional :timeout-ms per-rule timeout default 300000
   and :max-parallel max concurrent rules default 4).
   Returns a vector of per-rule result maps (same shape as analyze-rule, with
   :status :timeout or :error and an :error message on failures)."
  core/analyze-rules-parallel)

(def select-files-for-rule
  "Select repo files matching a rule's :rule/applies-to file globs.
   Args: repo-path (string repo root), rule (policy-pack rule map).
   Returns a lazy/clojure sequence of java.io.File, capped at the per-rule file
   limit, excluding files over the max size limit."
  core/select-files-for-rule)

(def behavioral-rules
  "Filter rules to those carrying :rule/knowledge-content (need LLM analysis).
   Args: pack-rules (sequence of policy-pack rule maps).
   Returns a vector of the matching rule maps."
  core/behavioral-rules)
