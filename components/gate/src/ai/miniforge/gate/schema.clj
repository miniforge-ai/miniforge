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
(ns ai.miniforge.gate.schema
  "Malli schemas for what the gate interface returns.

   A gate's check returns one of two shapes, and `interface/passed?`
   reads both: the gate convention `{:passed? bool :errors [...]}`, or a
   `response/success` / `response/error` map. A result in neither shape
   reads as failed, which is safe but silent: a gate that drifts out of
   both shapes fails every phase that runs it. These schemas name the
   shapes so instrumented tests catch that drift at the gate.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} GateVerdict
  "The gate convention: an explicit boolean verdict and its findings."
  [:map
   [:passed? :boolean]
   [:errors {:optional true} [:maybe [:sequential :map]]]])

(def ^{:stratum 0} RepairVerdict
  "The gate convention for repairs: an explicit boolean outcome."
  [:map
   [:success? :boolean]])

(def ^{:stratum 0} ResponseStatus
  "A `response/success` or `response/error` map."
  [:map
   [:status [:enum :success :error :failed]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} CheckResult
  "What `check-gate` returns: the gate's own result in either accepted
   shape, tagged with the gate that produced it."
  [:and
   [:map [:gate :keyword]]
   [:or GateVerdict ResponseStatus]])

(def ^{:stratum 1} RepairResult
  "What `repair-gate` returns: the gate's repair result in either
   accepted shape, tagged with the gate when the gate has a repair."
  [:or RepairVerdict ResponseStatus])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} ChecksResult
  "What `check-gates` returns: the overall verdict and every result."
  [:map {:closed true}
   [:passed? :boolean]
   [:results [:vector CheckResult]]
   [:failed-gates [:vector CheckResult]]])
