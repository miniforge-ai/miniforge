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
(ns ai.miniforge.policy-pack.interface.checking
  "Detection and artifact-checking helpers."
  (:require
   [ai.miniforge.policy-pack.compiler :as compiler]
   [ai.miniforge.policy-pack.core :as core]
   [ai.miniforge.policy-pack.detection :as detection]))

;------------------------------------------------------------------------------ Layer 0

;; Detection and checking
(def ^{:stratum 0} detect-violation
  "Detect a violation for one rule against an artifact, dispatching on
   :rule/detection :type. (detect-violation rule artifact context) returns a
   violation map (keys include :type :rule-id :matches :message) or nil when
   clean."
  detection/detect-violation)

(def ^{:stratum 0} detect-semantic
  "Run the LLM-as-judge detector for a heuristic :custom rule.
   (detect-semantic rule artifact context) returns a :type :semantic violation
   map when the judge reports violations, a :type :semantic-error map when the
   judge throws, or nil when clean or when the semantic wiring is absent from
   context."
  detection/detect-semantic)

(def ^{:stratum 0} check-rules
  "Check multiple rules against one artifact.
   (check-rules rules artifact context) returns a vector of
   {:rule rule :violation violation-map :timestamp Instant} for each rule that
   fired."
  detection/check-rules)

(def ^{:stratum 0} check-artifact
  "Check one artifact against all applicable rules from a pack (or vector of
   packs). (check-artifact pack artifact context) returns
   {:passed? bool :violations [...] :blocking [...] :require-approval [...]
   :warnings [...] :audits [...] :read-only? bool} where :passed? is true when
   there are no hard-halt blocking violations."
  core/check-artifact)

(def ^{:stratum 0} check-artifacts
  "Check several artifacts against pack rules.
   (check-artifacts pack artifacts context) returns a vector of check-artifact
   result maps, one per artifact."
  core/check-artifacts)

(def ^{:stratum 0} filter-applicable-rules
  "Filter rules to those enabled and applicable to a context (by file globs,
   task type, and phase). (filter-applicable-rules rules context) returns a
   vector of applicable rules."
  core/filter-applicable-rules)

(def ^{:stratum 0} rule-applies-to-phase?
  "Predicate. (rule-applies-to-phase? rule phase) returns true when the rule
   has no :phases constraint or its constraint contains phase."
  core/rule-applies-to-phase?)

(def ^{:stratum 0} classify-violations
  "Exhaustive violation classification: {:blocking :require-approval
   :warnings :audits :unknown}; no default-pass branch."
  detection/classify-violations)

(def ^{:stratum 0} violation->error
  "Convert a check-rules violation entry into a gate error map
   {:code :message :severity :location {:matches :path} :remediation}."
  detection/violation->error)

(def ^{:stratum 0} violation->warning
  "Convert a check-rules violation entry into a gate warning map
   {:code :message :severity}."
  detection/violation->warning)

;; Detection binding compiler / validator
(def ^{:stratum 0} resolve-detector
  "Return the detection-mechanism keyword a rule binds to: one of
   :content-scan/:diff-analysis/:plan-output/:state-comparison/:ast-analysis,
   or :capability/:custom/:semantic, or :none when it binds to no available
   mechanism. (resolve-detector rule)"
  compiler/resolve-detector)

(def ^{:stratum 0} rule-enabled?
  "Predicate. (rule-enabled? rule) returns true unless the rule explicitly sets
   :rule/enabled? to false; a missing flag means enabled."
  compiler/rule-enabled?)

(def ^{:stratum 0} compile-rule-check
  "Compile one enabled rule into an executable N4 check entry.
   (compile-rule-check rule) returns {:rule :detector :check-fn}, or an
   :invalid-input anomaly when the rule cannot bind to a detector."
  compiler/compile-rule-check)

(def ^{:stratum 0} compile-pack-checks
  "Compile every enabled rule in a pack into executable N4 check entries.
   (compile-pack-checks pack) returns {:ok true :rule-count :detector-counts
   :compiled-rules [...]}, or an :invalid-input anomaly naming unbindable
   rule ids."
  compiler/compile-pack-checks)

(def ^{:stratum 0} compile-pack
  "Validate that every enabled rule in a pack binds to a detection mechanism.
   (compile-pack pack) returns {:ok true :rule-count int :detector-counts {...}}
   on success, or an :invalid-input anomaly map (data, not a throw) naming
   :unbindable-rule-ids when any enabled rule resolves to :none."
  compiler/compile-pack)
