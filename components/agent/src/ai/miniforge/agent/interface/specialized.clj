;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.agent.interface.specialized
  "Specialized agent constructors, schemas, and helper utilities."
  (:require
   [ai.miniforge.agent.curator :as curator]
   [ai.miniforge.agent.implementer :as implementer]
   [ai.miniforge.agent.planner :as planner]
   [ai.miniforge.agent.releaser :as releaser]
   [ai.miniforge.agent.reviewer :as reviewer]
   [ai.miniforge.agent.reviewer.artifact :as reviewer-artifact]
   [ai.miniforge.agent.reviewer.issues :as reviewer-issues]
   [ai.miniforge.agent.tester :as tester]
   [ai.miniforge.agent.protocols.records.specialized :as specialized-records]
   [ai.miniforge.progress-detector.interface :as progress-detector]))

;------------------------------------------------------------------------------ Layer 0
;; Specialized agent support

(def create-base-agent
  "Create a base (functional) agent from callbacks. Arg: options map requiring
   :role :system-prompt :invoke-fn :validate-fn :repair-fn, with optional
   :config :logger. Returns a FunctionalAgent record (an Agent)."
  specialized-records/create-base-agent)

(def make-validator
  "Build a validate-fn from a Malli schema. Arg: malli-schema. Returns a fn of
   output -> {:valid? bool :errors nil-or-explanation}."
  specialized-records/make-validator)

(def cycle-agent
  "Run an invoke->validate->repair cycle on a specialized agent. Args: agent,
   context, input, optional :max-iterations (default 3). Returns the final
   result map after repair attempts."
  specialized-records/cycle-agent)

(def create-planner
  "Create a Planner agent. Arg (optional): options map (:config :logger
   :llm-backend). Returns a FunctionalAgent record (an Agent)."
  planner/create-planner)

(def create-implementer
  "Create an Implementer agent. Arg (optional): options map. Returns a
   FunctionalAgent record (an Agent)."
  implementer/create-implementer)

(def create-tester
  "Create a Tester agent. Arg (optional): options map. Returns a
   FunctionalAgent record (an Agent)."
  tester/create-tester)

(def create-reviewer
  "Create a Reviewer agent (LLM-backed review + deterministic gates, falling
   back to gate-only without an LLM). Arg (optional): options map
   (:gates :strict :logger :llm-backend :config). Returns a FunctionalAgent
   record (an Agent)."
  reviewer/create-reviewer)

(def create-releaser
  "Create a Releaser agent. Arg (optional): options map. Returns a
   FunctionalAgent record (an Agent)."
  releaser/create-releaser)

;; Curator is a multimethod (not an Agent record) — it post-processes the
;; environment state an agent left behind. Dispatches on `:curator/kind`:
;; - `:implement`        (default) — produces a CuratedArtifact.
;; - `:merge-resolution` (v2 §6.1.2) — validates the resolution agent's
;;                       iteration; surfaces :curator/markers-not-resolved
;;                       and :curator/recurring-conflict terminals.
(def curate
  "Curator entry point (a multimethod dispatching on :curator/kind). Arg:
   input map. Returns a response map whose :output is a CuratedArtifact on
   success, or an error response. Dispatch: :implement (default) and
   :merge-resolution."
  curator/curate)

(def curate-implement-output
  "Curate an implementer's result + environment state into a CuratedArtifact.
   Arg: input map (requires :implementer-result, :worktree-path; see impl for
   capsule-mode keys). Returns a success response with :output a
   CuratedArtifact, or an error response when no files were written. Thin
   wrapper over (curate (assoc input :curator/kind :implement))."
  curator/curate-implement-output)

(def CuratedArtifact
  "Malli schema (a [:map ...] vector) for the curator's structured output,
   consumed by verify/review/release/PR-doc phases. Keys include :code/id
   :code/files :code/summary :code/tests-added? :code/scope-deviations
   :code/breaking-change? :code/curated-at :code/curator-source."
  curator/CuratedArtifact)

(def CuratedFileEntry
  "Malli schema (a [:map ...] vector) for one file in a CuratedArtifact:
   :path (string) :content (string) :action (:create|:modify|:delete)."
  curator/FileEntry)

(def validate-curated-artifact
  "Validate a CuratedArtifact against its schema. Arg: artifact. Returns a
   validation result (schema/valid or schema/invalid map)."
  curator/validate-curated-artifact)

(def substantive-file?
  "True when a file entry represents real implementer work, not a
   runtime/session side-effect. Arg: file-entry map. Returns boolean."
  curator/substantive-file?)

(def non-substantive-paths
  "Set of file paths the curator drops before assessing 'files written'
   (runtime/session markers). A set of strings (currently
   #{\".miniforge-session-id\"}), not a vector."
  curator/non-substantive-paths)

(def Plan
  "Malli schema (a [:map ...] vector) for a planner's output: :plan/id
   :plan/name :plan/tasks (vector of PlanTask) plus optional complexity,
   risks, assumptions."
  planner/Plan)

(def PlanTask
  "Malli schema (a [:map ...] vector) for one task in a Plan: :task/id
   :task/description :task/type plus optional dependencies, acceptance
   criteria, effort, component, stratum, merge-strategy."
  planner/PlanTask)

(def CodeArtifact
  "Malli schema (a [:map ...] vector) for the implementer's output: :code/id
   :code/files (vector of CodeFile) plus optional dependencies-added,
   tests-needed?, language, summary."
  implementer/CodeArtifact)

(def CodeFile
  "Malli schema (a [:map ...] vector) for one code file: :path :content
   :action (:create|:modify|:delete)."
  implementer/CodeFile)

(def TestArtifact
  "Malli schema (a [:map ...] vector) for the tester's output: :test/id
   :test/files (vector of TestFile) :test/type plus optional coverage,
   framework, assertions/cases counts, summary."
  tester/TestArtifact)

(def TestFile
  "Malli schema (a [:map ...] vector) for one test file: :path :content."
  tester/TestFile)

(def Coverage
  "Malli schema (a [:map ...] vector) for test coverage: optional :lines
   :branches :functions, each a double 0.0-100.0."
  tester/Coverage)

(def ReviewArtifact
  "Malli schema (a [:map ...] vector) for the reviewer's output: :review/id
   :review/decision (enum) :review/gate-results :review/summary plus gate
   counts and optional blocking-issues, warnings, recommendations, issues,
   strengths."
  reviewer-issues/ReviewArtifact)

(def ReviewIssue
  "Malli schema (a [:map ...] vector) for a single review issue: :severity
   (:blocking|:warning|:nit) :description plus optional :file :line
   :suggestion (each nil-tolerant)."
  reviewer-issues/ReviewIssue)

(def GateFeedback
  "Malli schema (a [:map ...] vector) for feedback from one gate: :gate-id
   :gate-type :passed? plus optional errors, warnings, duration-ms."
  reviewer-issues/GateFeedback)

(def ReleaseArtifact
  "Malli schema (a [:map ...] vector) for the releaser's output:
   :release/id :release/branch-name :release/commit-message :release/pr-title
   :release/pr-description plus optional files-summary."
  releaser/ReleaseArtifact)

(def plan-summary
  "Summarize a plan for logging/display. Arg: plan. Returns a map
   {:id :name :task-count :complexity :risk-count}."
  planner/plan-summary)

(def task-dependency-order
  "Topologically sort a plan's tasks (dependency-free first). Arg: plan.
   Returns a vector of task maps; on a cycle, remaining tasks are appended."
  planner/task-dependency-order)

(def validate-plan
  "Validate a plan against the Plan schema and check structural issues
   (invalid/circular deps). Arg: plan. Returns {:valid? bool :errors ...}."
  planner/validate-plan)

(def code-summary
  "Summarize a code artifact for logging/display. Arg: artifact. Returns a
   map {:id :file-count :actions :language :tests-needed? :dependencies-added}."
  implementer/code-summary)

(def files-by-action
  "Group a code artifact's files by :action. Arg: artifact. Returns a map of
   action keyword -> vector of file maps."
  implementer/files-by-action)

(def total-lines
  "Count total lines of code in an artifact (excluding :delete files). Arg:
   artifact. Returns an int."
  implementer/total-lines)

(def validate-code-artifact
  "Validate a code artifact against the schema and check for issues (empty
   creates, duplicate paths). Arg: artifact. Returns a validation result
   (schema/valid or schema/invalid map)."
  implementer/validate-code-artifact)

(def test-summary
  "Summarize a test artifact for logging/display. Arg: artifact. Returns a
   map {:id :file-count :type :framework :assertions :cases :coverage}."
  tester/test-summary)

(def coverage-meets-threshold?
  "Check whether an artifact's coverage meets thresholds. Args: artifact,
   optional :lines :branches :functions (defaults 80/70/80). Returns boolean."
  tester/coverage-meets-threshold?)

(def tests-by-path
  "Map a test artifact's file paths to their content. Arg: artifact. Returns
   a map of path string -> content string."
  tester/tests-by-path)

(def validate-test-artifact
  "Validate a test artifact against the schema and check for issues (naming
   convention, missing assertions). Arg: artifact. Returns
   {:valid? bool :errors ...}."
  tester/validate-test-artifact)

(def review-summary
  "Summarize a review artifact for logging/display. Arg: artifact. Returns a
   map {:id :decision :gates-passed :gates-failed :gates-total
   :blocking-issues-count :warnings-count :llm-issues-count}."
  reviewer-artifact/review-summary)

(def approved?
  "True when a review artifact's :review/decision is :approved. Arg: artifact.
   Returns boolean."
  reviewer-artifact/approved?)

(def rejected?
  "True when a review artifact's :review/decision is :rejected. Arg: artifact.
   Returns boolean."
  reviewer-artifact/rejected?)

(def conditionally-approved?
  "True when a review artifact's :review/decision is :conditionally-approved.
   Arg: artifact. Returns boolean."
  reviewer-artifact/conditionally-approved?)

(def get-blocking-issues
  "Extract blocking issues from a review artifact. Arg: artifact. Returns a
   vector of strings (empty if none)."
  reviewer-artifact/get-blocking-issues)

(def get-review-warnings
  "Extract warnings from a review artifact. Arg: artifact. Returns a vector of
   strings (empty if none)."
  reviewer-artifact/get-warnings)

(def get-recommendations
  "Extract recommendations from a review artifact. Arg: artifact. Returns a
   vector of strings (empty if none)."
  reviewer-artifact/get-recommendations)

(def changes-requested?
  "True when a review artifact's :review/decision is :changes-requested. Arg:
   artifact. Returns boolean."
  reviewer-artifact/changes-requested?)

(def get-review-issues
  "Extract LLM review issues from a review artifact. Arg: artifact. Returns a
   vector of ReviewIssue maps (empty if none)."
  reviewer-artifact/get-issues)

(def get-review-strengths
  "Extract strengths noted by the LLM from a review artifact. Arg: artifact.
   Returns a vector of strings (empty if none)."
  reviewer-artifact/get-strengths)

(def validate-review-artifact
  "Validate a review artifact against the schema and check gate-count
   consistency. Arg: artifact. Returns {:valid? bool :errors ...}."
  reviewer-artifact/validate-review-artifact)

(def rejection-warnings-only?
  "True when a review is a rejection driven solely by warnings (no blocking
   issues). Arg: artifact. Returns boolean."
  reviewer-artifact/rejection-warnings-only?)
;; Repair-loop fingerprint helpers — moved to
;; components/progress-detector/.../detectors/repair-loop in Stage 2.
;; These re-exports stay for backward compatibility; phase-software-factory
;; reads them through this interface.
(def review-fingerprint
  "Reduce a review artifact to a stable, comparable fingerprint of its
   actionable items. Arg: review. Returns a sorted vector of
   [severity file line description] tuples (empty when nothing is actionable)."
  progress-detector/review-fingerprint)

(def review-stagnated?
  "True when a review's fingerprint exactly matches the prior one (repair
   loop made no actionable change). Args: prior (may be nil), current.
   Returns boolean; false on first iteration or empty current fingerprint."
  progress-detector/stagnated?)

(def release-summary
  "Summarize a release artifact for logging/display. Arg: artifact. Returns a
   map {:id :branch :pr-title :files-summary}."
  releaser/release-summary)

(def validate-release-artifact
  "Validate a release artifact against the schema and check for issues (branch
   name spaces, PR-title length, empty commit first line). Arg: artifact.
   Returns {:valid? bool :errors ...}."
  releaser/validate-release-artifact)
