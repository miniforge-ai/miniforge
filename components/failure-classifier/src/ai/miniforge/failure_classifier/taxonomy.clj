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

(ns ai.miniforge.failure-classifier.taxonomy
  "Canonical failure taxonomy per N1 §5.3.3.

   All workflow, agent, tool, and system failures MUST be classified into
   this canonical taxonomy. This enables structured failure analysis, SLI
   computation, and targeted remediation.")

;------------------------------------------------------------------------------ Layer 0
;; Canonical failure classes

(def failure-classes
  "The complete set of canonical failure classes.
   Every failure event MUST carry one of these as :failure/class."
  #{:failure.class/agent-error       ; Agent logic defect, prompt failure, hallucination
    :failure.class/task-code         ; User code, spec, or test failure
    :failure.class/tool-error        ; Tool returned an error or unexpected result
    :failure.class/external          ; Third-party service unavailable or errored
    :failure.class/policy            ; Policy gate or validation rejected execution
    :failure.class/resource          ; Budget exhausted (tokens, time, retries, cost)
    :failure.class/timeout           ; Wall-clock or capability TTL exceeded
    :failure.class/concurrency       ; Deadlock, resource lock contention, merge conflict
    :failure.class/data-integrity    ; Content hash mismatch, stale context, schema violation
    :failure.class/unknown})         ; Unclassified failure — treat as SLI incident

(def FailureClass
  "Malli schema for a failure class keyword."
  (into [:enum] (sort failure-classes)))

(def ClassifiedFailure
  "Malli schema for a classified failure record."
  [:map
   [:failure/class FailureClass]
   [:failure/message :string]
   [:failure/context {:optional true} :map]])

;------------------------------------------------------------------------------ Layer 0
;; Predicates

(defn valid-failure-class?
  "Returns true if the keyword is a valid canonical failure class."
  [kw]
  (contains? failure-classes kw))

(defn unknown-class?
  "Returns true if the failure class is :failure.class/unknown.
   Unknown failures MUST be treated as SLI incidents (N1 §5.3.3)."
  [kw]
  (= kw :failure.class/unknown))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (valid-failure-class? :failure.class/timeout)    ;; => true
  (valid-failure-class? :failure.class/bogus)      ;; => false
  (unknown-class? :failure.class/unknown)          ;; => true

  :leave-this-here)
