;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.connector-linter.interface
  "Public API for data-driven linter integration."
  (:require
   [ai.miniforge.connector-linter.etl :as etl]
   [ai.miniforge.connector-linter.runner :as runner]))

;; ETL
(def apply-mapping
  "Apply a mapping spec to raw linter output, producing canonical violations.
   Args: mapping (spec map from linter-mappings.edn), output (raw linter output
   string). Returns a vector of violation maps (empty when nothing matches);
   never nil."
  etl/apply-mapping)
(def get-mapping
  "Look up a mapping spec by linter ID. Args: linter-id (keyword). Returns the
   mapping map, or nil if no mapping is registered for that ID."
  etl/get-mapping)
(def mappings
  "All linter mappings loaded from classpath EDN, as a `delay`. Deref to obtain
   a map of {linter-id mapping-spec}; yields {} when the resource is absent."
  etl/mappings)

;; Runner
(def linter-available?
  "Check whether a linter CLI is available on PATH. Args: check-cmd (command
   vector). Returns boolean; false on any subprocess failure."
  runner/linter-available?)
(def run-linter
  "Run a single linter and parse its output via the ETL mapping. Args:
   repo-path, tech-id (keyword), linter-config (map). Returns a map
   {:tech keyword :violations vector :available? boolean :duration-ms int}."
  runner/run-linter)
(def run-all
  "Run linters for all detected technologies. Args: repo-path, fingerprints
   (seq of tech fingerprint maps), detected-techs (set of tech-id keywords).
   Returns a map {:linter-results vector :violations vector
   :total-violations int :total-duration-ms int}."
  runner/run-all)
(def run-fixes
  "Run linter fix commands for detected technologies. Args: repo-path,
   fingerprints (seq of tech fingerprint maps), detected-techs (set of tech-id
   keywords). Returns a map {:fixed vector} of per-tech {:tech :exit} results."
  runner/run-fixes)
