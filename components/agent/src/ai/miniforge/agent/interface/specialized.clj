(ns ai.miniforge.agent.interface.specialized
  "Specialized agent constructors, schemas, and helper utilities."
  (:require
   [ai.miniforge.agent.implementer :as implementer]
   [ai.miniforge.agent.planner :as planner]
   [ai.miniforge.agent.releaser :as releaser]
   [ai.miniforge.agent.reviewer :as reviewer]
   [ai.miniforge.agent.tester :as tester]
   [ai.miniforge.agent.protocols.records.specialized :as specialized-records]))

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
(def release-summary releaser/release-summary)
(def validate-release-artifact releaser/validate-release-artifact)
