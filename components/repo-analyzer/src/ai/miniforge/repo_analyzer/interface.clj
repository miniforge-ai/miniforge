;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.repo-analyzer.interface
  "Public API for repository technology and policy-pack analysis."
  (:require
   [ai.miniforge.repo-analyzer.core :as core]))

(def fingerprints
  "Technology fingerprints loaded from classpath EDN, as a delay."
  core/fingerprints)

(def detect-languages
  "Detect technologies by file extension. Args: repo-path. Returns tech IDs."
  core/detect-languages)

(def detect-markers
  "Detect technologies by repository markers. Args: repo-path. Returns tech IDs."
  core/detect-markers)

(def detect-git-host
  "Detect git host from repository remote URL. Args: repo-path."
  core/detect-git-host)

(def select-packs
  "Select policy packs based on detected tech IDs."
  core/select-packs)

(def analyze-repo
  "Analyze a repository and return technologies, git host, and selected packs."
  core/analyze-repo)
