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

(ns ai.miniforge.compliance-scanner.interface
  "Public API for the compliance-scanner component.

   Thin pass-throughs only — no implementation logic here.

   Layer 0: Schema re-exports
   Layer 1: Phase entry points
   Layer 2: Convenience run! orchestrator"
  (:require [ai.miniforge.compliance-scanner.schema             :as schema]
            [ai.miniforge.compliance-scanner.factory            :as factory]
            [ai.miniforge.compliance-scanner.scan               :as scan]
            [ai.miniforge.compliance-scanner.classify           :as classify]
            [ai.miniforge.compliance-scanner.plan               :as plan]
            [ai.miniforge.compliance-scanner.report             :as report]
            [ai.miniforge.compliance-scanner.execute            :as execute]
            [ai.miniforge.compliance-scanner.comments           :as comments]
            [ai.miniforge.compliance-scanner.exceptions-as-data :as exc-data]
            [ai.miniforge.compliance-scanner.named-constants    :as named-const]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def Violation
  "Malli schema (a `[:map ...]` vector) for a single detected rule breach.
   Keys: :rule/id (keyword), :rule/category (non-empty string),
   :rule/title (string), :file (string), :line (int), :current (string),
   :suggested (string or nil), :auto-fixable? (boolean), :rationale (string)."
  schema/Violation)
(def ScanResult
  "Malli schema (a `[:map ...]` vector) for the output of the scan phase.
   Keys: :violations (vector of Violation), :rules-scanned (vector of
   keywords), :files-scanned (int), :scan-duration-ms (int)."
  schema/ScanResult)
(def PlanTask
  "Malli schema (a `[:map ...]` vector) for one DAG task node in a Plan.
   Keys: :task/id (uuid), :task/deps (set of uuids), :task/file (string),
   :task/rule-id (keyword), :task/violations (vector of Violation)."
  schema/PlanTask)
(def Plan
  "Malli schema (a `[:map ...]` vector) for the full remediation plan.
   Keys: :dag-tasks (vector of PlanTask), :work-spec (string),
   :summary (PlanSummary)."
  schema/Plan)
(def PlanSummary
  "Malli schema (a `[:map ...]` vector) of aggregate plan counts.
   Keys: :total-violations, :auto-fixable, :needs-review, :files-affected,
   :rules-violated (all ints)."
  schema/PlanSummary)

;------------------------------------------------------------------------------ Layer 0
;; Factory re-exports

(def ->violation
  "Construct a Violation map from positional args:
   [rule-id rule-category title file line current suggested auto-fixable?
   rationale]. Pure; returns the map."
  factory/->violation)
(def ->scan-result
  "Construct a ScanResult map from positional args:
   [violations rules-scanned files-scanned scan-duration-ms]. Pure;
   returns the map."
  factory/->scan-result)
(def ->plan-summary
  "Derive a PlanSummary map from a collection of violations by counting
   totals, auto-fixable, needs-review, distinct files, and distinct rules.
   Pure; returns the map."
  factory/->plan-summary)
(def ->plan-task
  "Construct a PlanTask map from positional args:
   [id deps file rule-id violations]. Pure; returns the map."
  factory/->plan-task)
(def ->plan
  "Construct a Plan map from positional args:
   [dag-tasks work-spec summary]. Pure; returns the map."
  factory/->plan)
(def ->delta-report
  "Construct a DeltaReport map from positional args:
   [repo-path standards-path scan-timestamp summary violations]. Pure;
   returns the map."
  factory/->delta-report)
(def DeltaReport
  "Malli schema (a `[:map ...]` vector) for the delta report written to
   disk. Keys: :repo-path (string), :standards-path (string),
   :scan-timestamp (string), :summary (PlanSummary), :violations (vector
   of Violation)."
  schema/DeltaReport)

;------------------------------------------------------------------------------ Layer 0
;; Built-in linter re-exports
;;
;; Standalone Clojure-AST linters that the compliance scanner runs in
;; addition to the regex-driven pack rules.

(def exceptions-as-data-rule-id
  "Stable rule id for the exceptions-as-data linter."
  exc-data/rule-id)

(defn scan-exceptions-as-data
  "Run the exceptions-as-data linter against `repo-root`. Returns a map
   with `:violations`, `:files-scanned`, `:counts`, and `:rule/id`."
  [repo-root]
  (exc-data/scan-repo repo-root))

(def named-constants-rule-id
  "Stable rule id for the named-constants locator."
  named-const/rule-id)

(defn scan-named-constants
  "Locate magic NUMERIC literals against `repo-root` (Dewey 006). Deterministic,
   LLM-free MEASUREMENT: returns `:violations` (each with `:file`, `:line`,
   `:column`, `:current` (the literal token), `:kind` `:magic-numeric`, and
   `:rule/id`), plus `:files-scanned`, `:counts` {:magic-numeric N}, `:rule/id`.
   A triage LOCATOR, not a gate — it
   over-reports (no math-identity / self-documenting-keyword exemptions) and does
   not cover structured-string magic literals; the semantic judge still owns
   those and the fix (extraction needs a name + intent docstring)."
  [repo-root]
  (named-const/scan-repo repo-root))

;------------------------------------------------------------------------------ Layer 1
;; Phase entry points

(defn scan
  "Scan a repo for violations across all configured rules.

   Arguments:
   - repo-path      - string path to repo root
   - standards-path - string path to .standards dir
   - opts           - map with :rules (default :all), :since (git ref, optional)

   Returns ScanResult map."
  [repo-path standards-path opts]
  (scan/scan-repo repo-path standards-path opts))

(defn classify
  "Add :auto-fixable? and :rationale to each violation.

   Arguments:
   - violations - vector of raw Violation maps from scan

   Returns updated violation vector."
  [violations]
  (classify/classify-violations violations))

(defn plan
  "Generate DAG task defs and markdown work spec from classified violations.

   Arguments:
   - violations - vector of classified Violation maps
   - repo-path  - string path to repo root

   Returns Plan map."
  [violations repo-path]
  (plan/plan violations repo-path))

(defn write-report!
  "Write EDN delta report to .miniforge/compliance-report.edn.

   Arguments:
   - delta-report - DeltaReport map
   - repo-path    - string path to repo root

   Returns the path written."
  [delta-report repo-path]
  (report/write-report! delta-report repo-path))

(defn write-work-spec!
  "Write markdown work spec to docs/compliance/YYYY-MM-DD-compliance-delta.md.

   Arguments:
   - work-spec - markdown string
   - repo-path - string path to repo root

   Returns the path written."
  [work-spec repo-path]
  (report/write-work-spec! work-spec repo-path))

(defn write-delta-report!
  "Build and write EDN delta report from classified violations and plan.

   Arguments:
   - repo-path      - string path to repo root
   - standards-path - string path to .standards dir
   - classified     - vector of classified Violation maps (from classify phase)
   - plan           - Plan map (for :summary)

   Returns the path written."
  [repo-path standards-path classified plan]
  (let [timestamp    (.toString (java.time.Instant/now))
        delta-report (factory/->delta-report repo-path standards-path timestamp
                                             (:summary plan) classified)]
    (write-report! delta-report repo-path)))

(defn execute!
  "Apply all auto-fixable violations and create one PR per rule.

   Arguments:
   - plan      - Plan map with :dag-tasks (from the plan phase)
   - repo-path - string path to the repo/worktree root

   Returns map with :prs (vector of per-rule results), :violations-fixed, :files-changed."
  [plan repo-path]
  (execute/execute! plan repo-path))

;------------------------------------------------------------------------------ Layer 1
;; PR-scoped review entry point (N13 §2.2)

(def violation->comment
  "Render a single classified Violation to a PR review comment per
   N13 §2.3."
  comments/violation->comment)

(def violations->comments
  "Render a vector of classified Violations to a vector of PR review
   comment records per N13 §2.3."
  comments/violations->comments)

(def extract-comment-payload
  "Recover the embedded `:comment/payload` map from a comment body.
   Used by the Comment Response Agent to parse posted policy
   violations."
  comments/extract-payload)

(defn pr-review
  "PR-scoped read-only standards review per N13 §2.2.

   Runs scan with `:since base-ref` (incremental + diff-analysis modes),
   classifies the resulting violations, and renders them to the comment
   record shape defined in N13 §2.3 — without applying any fixes.

   Arguments:
   - repo-path      - string path to repo root (a checkout of the PR's
                      head SHA)
   - standards-path - string path to .standards dir
   - opts           - map with:
       :base-ref     - REQUIRED. Git ref of the PR's base branch (e.g.,
                       \"origin/main\"). Passed through to scan as :since.
       :rules        - rule selector, default :all
       :pack-info    - {:pack/id <string> :pack/version <string>}.
                       Embedded in every rendered comment payload.
                       Defaults to {:pack/id \"unknown\" :pack/version \"0.0.0\"}
                       if not supplied — callers SHOULD provide real values.
       :pack         - pre-compiled PackManifest (optional)

   Returns:

     {:pr-review/scan-result <ScanResult>
      :pr-review/classified  <vector of classified Violations>
      :pr-review/comments    <vector of comment records, ready to post>
      :pr-review/summary     {:total <int>
                              :auto-fixable <int>
                              :needs-review <int>
                              :files-affected <int>
                              :rules-violated <int>}}

   This function does NOT post comments and does NOT apply fixes. The
   caller (a step workflow / CLI / janitor) consumes the
   :pr-review/comments vector and posts via `connector-github` (or the
   appropriate provider connector).

   See N13 §2.2 — Standards Reviewer (reuse compliance-scanner)."
  [repo-path standards-path
   {:keys [base-ref rules pack pack-info]
    :or   {rules :all}
    :as   _opts}]
  (when-not (and (string? base-ref) (seq base-ref))
    (throw (ex-info "pr-review requires :base-ref (e.g., \"origin/main\")"
                    {:opts _opts :anomaly :pr-review/missing-base-ref})))
  (let [scan-opts    (cond-> {:rules rules :since base-ref}
                       pack (assoc :pack pack))
        scan-result  (scan repo-path standards-path scan-opts)
        classified   (classify (:violations scan-result))
        pi           (or pack-info {:pack/id "unknown" :pack/version "0.0.0"})
        comment-recs (violations->comments classified pi)
        total        (count classified)
        auto-fix     (count (filter :auto-fixable? classified))]
    {:pr-review/scan-result scan-result
     :pr-review/classified  classified
     :pr-review/comments    comment-recs
     :pr-review/summary     {:total          total
                             :auto-fixable   auto-fix
                             :needs-review   (- total auto-fix)
                             :files-affected (count (distinct (mapv :file classified)))
                             :rules-violated (count (distinct (mapv :rule/id classified)))}}))

;------------------------------------------------------------------------------ Layer 2
;; Convenience orchestrator

(defn compliance-run!
  "Convenience: scan → classify → plan → write-report! → write-work-spec!

   Arguments:
   - repo-path      - string path to repo root
   - standards-path - string path to .standards dir
   - opts           - map with :rules (default :all), :since (optional)

   Returns Plan map (also writes reports to disk)."
  [repo-path standards-path opts]
  (let [scan-result  (scan repo-path standards-path opts)
        classified   (classify (:violations scan-result))
        the-plan     (plan classified repo-path)
        timestamp    (.toString (java.time.Instant/now))
        delta-report (factory/->delta-report
                      repo-path
                      standards-path
                      timestamp
                      (:summary the-plan)
                      classified)]
    (write-report! delta-report repo-path)
    (write-work-spec! (:work-spec the-plan) repo-path)
    the-plan))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Scan the current repo with all rules
  (def result (scan "." ".standards" {:rules :all}))
  (count (:violations result))

  ;; Classify and plan
  (def classified (classify (:violations result)))
  (def the-plan (plan classified "."))
  (println (:work-spec the-plan))

  ;; Full pipeline
  (def p (compliance-run! "." ".standards" {:rules :all}))
  (:summary p)

  :leave-this-here)
