;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.limits
  "Operational limits for semantic analysis, read from EDN at load time.

   One home for the analyzer's budgets: `selection` enforces the size and
   count caps, `core` enforces the per-rule timeout.

   Layer 0: the raw limits map
   Layer 1: the individual limits with their defaults (over Layer 0)"
  (:require
   [ai.miniforge.config.interface :as config]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private limits
  (config/read-config-resource-or "config/semantic-analyzer/limits.edn" {}))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} max-file-size-bytes
  "Maximum file size to send to LLM. Files larger than this are skipped."
  (get limits :max-file-size-bytes 50000))

(def ^{:stratum 1} max-files-per-rule
  "Maximum number of files to analyze per rule.
   Kept low to stay within per-rule timeout when using CLI backends."
  (get limits :max-files-per-rule 5))

(def ^{:stratum 1} judge-answer-excerpt-chars
  "How much of an unparseable judge answer a judge-failure message quotes."
  (get limits :judge-answer-excerpt-chars 200))

(def ^{:stratum 1} default-rule-timeout-ms
  "Maximum time to spend analyzing one rule before giving up.
   5 files × ~30s per CLI backend call = ~150s, so 300s gives headroom."
  (get limits :default-rule-timeout-ms 300000))
