;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.producer-fixtures
  "Live producer shapes shared by projection and canonical validation regressions.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pr-number 42)

(def ^{:stratum 0} pr-url "https://github.com/example/project/pull/42")

(def ^{:stratum 0} finished-at (java.time.Instant/parse "2026-09-30T12:00:00Z"))

(defn ^{:stratum 0} workflow-state [overrides]
  (merge {:workflow/status :completed} overrides))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} release-info []
  {:pr-number pr-number :pr-url pr-url :branch "feature/example" :commit-sha "abc123"})

(defn ^{:stratum 1} legacy-pr-info []
  {:number pr-number :url pr-url :status :merged :merged-at finished-at})

(defn ^{:stratum 1} incomplete-execution-output []
  {:evidence/execution-mode :local
   :evidence/runtime-class nil
   :evidence/task-started-at nil
   :evidence/task-finished-at finished-at
   :artifacts []})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} producer-state [error]
  (workflow-state {:workflow/status :failed
                   :workflow/pr-info (release-info)
                   :execution/output (incomplete-execution-output)
                   :workflow/error error}))
