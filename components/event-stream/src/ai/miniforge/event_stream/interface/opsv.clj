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
(ns ai.miniforge.event-stream.interface.opsv
  "Public schemas and constructors for the N3 OPSV event family."
  (:require
   [ai.miniforge.event-stream.opsv :as opsv]
   [ai.miniforge.event-stream.schema.opsv :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ExperimentPlanned
  "Schema for :opsv.experiment/planned."
  schema/ExperimentPlanned)

(def ^{:stratum 0} ExperimentStarted
  "Schema for :opsv.experiment/started."
  schema/ExperimentStarted)

(def ^{:stratum 0} LoadStep "Schema for :opsv/load-step." schema/LoadStep)

(def ^{:stratum 0} GuardrailAbort
  "Schema for :opsv.guardrail/abort."
  schema/GuardrailAbort)

(def ^{:stratum 0} ConvergenceIteration
  "Schema for :opsv.convergence/iteration."
  schema/ConvergenceIteration)

(def ^{:stratum 0} PolicyProposed
  "Schema for :opsv.policy/proposed."
  schema/PolicyProposed)

(def ^{:stratum 0} VerificationResult
  "Schema for :opsv.verification/result."
  schema/VerificationResult)

(def ^{:stratum 0} ActuationEmitted
  "Schema for :opsv.actuation/emitted."
  schema/ActuationEmitted)

(def ^{:stratum 0} DriftDetected
  "Schema for :opsv.drift/detected."
  schema/DriftDetected)

(def ^{:stratum 0} experiment-planned
  "Construct an experiment-planned event."
  opsv/experiment-planned)

(def ^{:stratum 0} experiment-started
  "Construct an experiment-started event."
  opsv/experiment-started)

(def ^{:stratum 0} load-step "Construct a load-step event." opsv/load-step)

(def ^{:stratum 0} guardrail-abort
  "Construct a guardrail-abort event."
  opsv/guardrail-abort)

(def ^{:stratum 0} convergence-iteration
  "Construct a convergence-iteration event."
  opsv/convergence-iteration)

(def ^{:stratum 0} policy-proposed
  "Construct a policy-proposed event."
  opsv/policy-proposed)

(def ^{:stratum 0} verification-result
  "Construct a verification-result event."
  opsv/verification-result)

(def ^{:stratum 0} actuation-emitted
  "Construct an actuation-emitted event."
  opsv/actuation-emitted)

(def ^{:stratum 0} drift-detected
  "Construct a drift-detected event."
  opsv/drift-detected)
