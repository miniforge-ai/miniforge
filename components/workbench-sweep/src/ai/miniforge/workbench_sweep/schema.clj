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

(ns ai.miniforge.workbench-sweep.schema
  "Malli schema for a sweep configuration — the declarative matrix the
   sweep runner executes: variants x replicates, each run producing one
   variant-tagged workbench snapshot.")

(def VariantRef
  [:map {:closed true}
   [:id :string]
   [:version {:optional true} :string]])

(def SweepVariant
  [:map {:closed true}
   ;; Column label in the comparison matrix. Replicates SHARE the label
   ;; (the kernel groups replicates by label; run ids stay distinct).
   [:label [:string {:min 1}]]
   [:workflow {:optional true} VariantRef]
   [:prompt {:optional true} VariantRef]
   [:model {:optional true} :string]
   [:method {:optional true} :string]
   [:axes {:optional true} [:map-of :keyword :string]]
   ;; Extra `{placeholder}` values substituted into the command template
   ;; for this variant (e.g. the chain id the command should execute).
   [:bindings {:optional true} [:map-of :keyword :string]]
   ;; Pre-produced PolicyEvaluation EDN for this variant — skips command
   ;; execution (re-projection of runs executed elsewhere).
   [:policy-eval {:optional true} :string]])

(def SweepConfig
  [:map {:closed true}
   [:experiment-id [:string {:min 1}]]
   [:out-dir [:string {:min 1}]]
   [:registry-version [:string {:min 1}]]
   [:replicates {:optional true} pos-int?]
   [:packs-dir {:optional true} :string]
   ;; Fallback pack scope: gate events don't carry pack ids, so a run's
   ;; harvested PolicyEvaluation may arrive with an EMPTY packs-applied —
   ;; which would project zero per-rule rows. When set, these pack ids
   ;; are assumed applied for any evaluation that carries none. Declare
   ;; exactly the packs the swept chain enforces.
   [:packs-applied {:optional true} [:vector :string]]
   ;; Command template: one argv, executed once per (variant x replicate)
   ;; with {label} {replicate} {out} + the variant's :bindings
   ;; substituted. The command MUST write a PolicyEvaluation EDN to {out}.
   ;; Optional when every variant carries :policy-eval.
   [:command {:optional true} [:vector :string]]
   [:variants [:vector {:min 1} SweepVariant]]])
