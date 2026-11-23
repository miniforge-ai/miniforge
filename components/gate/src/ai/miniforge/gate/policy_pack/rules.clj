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
(ns ai.miniforge.gate.policy-pack.rules
  "Selects the pack rules a gate evaluation runs: by phase, by
   applicability to the artifact, and for semantic rules by whether the
   judge acts on them."
  (:require
   [ai.miniforge.gate.policy-pack.artifact :as artifact]
   [ai.miniforge.policy-pack.interface :as policy-pack]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private judge-acting-actions
  "Enforcement actions for which the LLM judge actually runs. A semantic rule
   that only warns/audits is guidance — surfaced via behavior injection, not
   evaluated by the judge — so the run never pays for an LLM call that could
   only produce a non-blocking finding."
  #{:hard-halt :require-approval})

(defn- ^{:stratum 0} compiled-rules
  [compile-results]
  (mapcat :compiled-rules compile-results))

(defn- ^{:stratum 0} phase-compiled-rule?
  [phase compiled]
  (policy-pack/rule-applies-to-phase? (:rule compiled) phase))

(defn- ^{:stratum 0} rule-applicable-to-artifact?
  [rule phase context artifact]
  (seq (policy-pack/filter-applicable-rules
        [rule]
        (assoc context :phase phase :artifact artifact))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} phase-compiled-rules
  [compile-results phase]
  (filterv #(phase-compiled-rule? phase %) (compiled-rules compile-results)))

(defn ^{:stratum 1} semantic-judge-applies?
  "True unless this is a semantic-detector rule whose enforcement action is
   non-acting. Non-semantic detectors (content-scan, diff, etc.) always run —
   they are cheap and deterministic."
  [compiled]
  (or (not= :semantic (:detector compiled))
      (contains? judge-acting-actions
                 (get-in compiled [:rule :rule/enforcement :action]))))

(defn ^{:stratum 1} guidance-only-semantic?
  "A semantic-detector rule with a non-acting enforcement action — surfaced as
   guidance (behavior injection), never evaluated by the judge (see
   `semantic-judge-applies?`). Evidence marks these `:guidance`, not `:passed`,
   so the event log never claims the judge cleared a rule it skipped."
  [rule]
  (and (= :semantic (policy-pack/resolve-detector rule))
       (not (contains? judge-acting-actions
                       (get-in rule [:rule/enforcement :action])))))

(defn ^{:stratum 1} applicable-artifacts
  [rule phase artifact context]
  (filterv #(rule-applicable-to-artifact? rule phase context %)
           (artifact/artifact-inputs artifact)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} acting-semantic-rules
  "The enabled rules that resolve to the semantic judge AND carry an acting
   enforcement action AND are applicable to this run's `artifact` — exactly the
   rules the batched judge evaluates together. Applicability uses the same
   `applicable-artifacts` predicate the compiled per-rule path uses (phase +
   file-glob + artifact/task constraints), so the batched prompt never includes
   a rule that compiled evaluation would skip for this run."
  [packs phase artifact ctx]
  (->> (policy-pack/resolve-rules (mapcat :pack/rules packs))
       (filterv policy-pack/rule-enabled?)
       (filterv #(policy-pack/rule-applies-to-phase? % phase))
       (filterv #(= :semantic (policy-pack/resolve-detector %)))
       (filterv #(contains? judge-acting-actions
                            (get-in % [:rule/enforcement :action])))
       (filterv #(seq (applicable-artifacts % phase artifact ctx)))))
