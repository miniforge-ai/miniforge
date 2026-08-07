;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.gate.pre-verify-lint.execution
  "Linter execution for the pre-verify lint gate.

   Resolves the worktree to lint, runs the configured linters over the
   technologies the artifact touches, and translates linter violations
   into the gate's mechanical error shape.

   Layer 0: Worktree resolution, violation translation, and repair (all
            independent of each other)
   Layer 1: Check entry point, composing Layer 0 plus the technology
            detector and the linter runner"
  (:require
   [ai.miniforge.connector-linter.interface :as linter]
   [ai.miniforge.gate.pre-verify-lint.technology :as technology]
   [ai.miniforge.policy-clause.interface :as clause]
   [ai.miniforge.repo-analyzer.interface :as repo-analyzer]))

;------------------------------------------------------------------------------ Layer 0

;; Linter execution
(defn- ^{:stratum 0} resolve-worktree
  "Get the worktree path from context."
  [ctx]
  (or (get ctx :execution/worktree-path)
      (get ctx :worktree-path)
      "."))

(defn- ^{:stratum 0} violation->lint-error
  "Convert a linter violation to a gate error map. The violation carries a
   POLICY severity (:rule/severity); the gate error map speaks the
   mechanical scale, so it crosses via policy-clause — the one crossing
   point between the two axes. Rule severities are schema-validated, so an
   unknown value is a programmer error: the throw lands in check-gate's
   exception-to-failed-gate seam (fail-closed)."
  [v]
  (let [mech (clause/severity->mechanical (get v :rule/severity :high))]
    (when-not (keyword? mech)
      (throw (ex-info "Unknown rule severity on lint violation" {:anomaly mech})))
    {:type     :lint-error
     :file     (get v :file)
     :line     (get v :line 0)
     :message  (get v :current "")
     :rule-id  (get v :rule/id)
     :severity mech}))

(defn ^{:stratum 0} repair-pre-verify-lint
  "Lint errors cannot be auto-repaired — return to agent."
  [_artifact errors _ctx]
  {:success? false
   :errors errors})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} check-pre-verify-lint
  "Run configured linters on the worktree before verify."
  [artifact ctx]
  (let [worktree   (resolve-worktree ctx)
        techs      (technology/detect-technologies artifact)
        fps        @repo-analyzer/fingerprints
        result     (linter/run-all worktree fps techs)
        violations (:violations result)]
    (if (empty? violations)
      {:passed? true}
      {:passed? false
       :errors (mapv violation->lint-error violations)})))
