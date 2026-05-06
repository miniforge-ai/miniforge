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
   [ai.miniforge.agent.tester :as tester]
   [ai.miniforge.agent.protocols.records.specialized :as specialized-records]
   [ai.miniforge.progress-detector.detectors.repair-loop :as repair-loop]))

;------------------------------------------------------------------------------ Layer 0
;; Specialized agent support

(def create-base-agent specialized-records/create-base-agent)
(def make-validator specialized-records/make-validator)
(def cycle-agent specialized-records/cycle-agent)

(def create-planner planner/create-planner)
(def create-implementer implementer/create-implementer)
(def create-tester tester/create-tester)
(def create-reviewer reviewer/create-reviewer)
(def create-releaser releaser/create-releaser)

;; Curator is a multimethod (not an Agent record) — it post-processes the
;; environment state an agent left behind. Dispatches on `:curator/kind`:
;; - `:implement`        (default) — produces a CuratedArtifact.
;; - `:merge-resolution` (v2 §6.1.2) — validates the resolution agent's
;;                       iteration; surfaces :curator/markers-not-resolved
;;                       and :curator/recurring-conflict terminals.
(def curate curator/curate)
(def curate-implement-output curator/curate-implement-output)
(def CuratedArtifact curator/CuratedArtifact)
(def CuratedFileEntry curator/FileEntry)
(def validate-curated-artifact curator/validate-curated-artifact)
(def substantive-file? curator/substantive-file?)
(def non-substantive-paths curator/non-substantive-paths)

(def Plan planner/Plan)
(def PlanTask planner/PlanTask)
(def CodeArtifact implementer/CodeArtifact)
(def CodeFile implementer/CodeFile)
(def TestArtifact tester/TestArtifact)
(def TestFile tester/TestFile)
(def Coverage tester/Coverage)
(def ReviewArtifact reviewer/ReviewArtifact)
(def ReviewIssue reviewer/ReviewIssue)
(def GateFeedback reviewer/GateFeedback)
(def ReleaseArtifact releaser/ReleaseArtifact)

(def plan-summary planner/plan-summary)
(def task-dependency-order planner/task-dependency-order)
(def validate-plan planner/validate-plan)
(def code-summary implementer/code-summary)
(def files-by-action implementer/files-by-action)
(def total-lines implementer/total-lines)
(def validate-code-artifact implementer/validate-code-artifact)
(def test-summary tester/test-summary)
(def coverage-meets-threshold? tester/coverage-meets-threshold?)
(def tests-by-path tester/tests-by-path)
(def validate-test-artifact tester/validate-test-artifact)
(def review-summary reviewer/review-summary)
(def approved? reviewer/approved?)
(def rejected? reviewer/rejected?)
(def conditionally-approved? reviewer/conditionally-approved?)
(def get-blocking-issues reviewer/get-blocking-issues)
(def get-review-warnings reviewer/get-warnings)
(def get-recommendations reviewer/get-recommendations)
(def changes-requested? reviewer/changes-requested?)
(def get-review-issues reviewer/get-issues)
(def get-review-strengths reviewer/get-strengths)
(def validate-review-artifact reviewer/validate-review-artifact)
;; Repair-loop fingerprint helpers — moved to
;; components/progress-detector/.../detectors/repair-loop in Stage 2.
;; These re-exports stay for backward compatibility; phase-software-factory
;; reads them through this interface.
(def review-fingerprint repair-loop/review-fingerprint)
(def review-stagnated?  repair-loop/stagnated?)
(def release-summary releaser/release-summary)
(def validate-release-artifact releaser/validate-release-artifact)
