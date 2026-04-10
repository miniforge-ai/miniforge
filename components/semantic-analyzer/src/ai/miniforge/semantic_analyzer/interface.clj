;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.semantic-analyzer.interface
  "Public API for LLM-based semantic code analysis."
  (:require
   [ai.miniforge.semantic-analyzer.core :as core]))

(def build-judge-prompt core/build-judge-prompt)
(def analyze-file core/analyze-file)
(def analyze-rule core/analyze-rule)
(def select-files-for-rule core/select-files-for-rule)
(def behavioral-rules core/behavioral-rules)
