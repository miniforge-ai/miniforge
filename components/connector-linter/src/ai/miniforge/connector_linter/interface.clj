;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.connector-linter.interface
  "Public API for data-driven linter integration."
  (:require
   [ai.miniforge.connector-linter.etl :as etl]
   [ai.miniforge.connector-linter.runner :as runner]))

;; ETL
(def apply-mapping etl/apply-mapping)
(def get-mapping etl/get-mapping)
(def mappings etl/mappings)

;; Runner
(def linter-available? runner/linter-available?)
(def run-linter runner/run-linter)
(def run-all runner/run-all)
(def run-fixes runner/run-fixes)
