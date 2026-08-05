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

(def ^{:stratum 0} ExperimentPlanned schema/ExperimentPlanned)

(def ^{:stratum 0} ExperimentStarted schema/ExperimentStarted)

(def ^{:stratum 0} LoadStep schema/LoadStep)

(def ^{:stratum 0} GuardrailAbort schema/GuardrailAbort)

(def ^{:stratum 0} ConvergenceIteration schema/ConvergenceIteration)

(def ^{:stratum 0} PolicyProposed schema/PolicyProposed)

(def ^{:stratum 0} VerificationResult schema/VerificationResult)

(def ^{:stratum 0} ActuationEmitted schema/ActuationEmitted)

(def ^{:stratum 0} DriftDetected schema/DriftDetected)

(def ^{:stratum 0} experiment-planned opsv/experiment-planned)

(def ^{:stratum 0} experiment-started opsv/experiment-started)

(def ^{:stratum 0} load-step opsv/load-step)

(def ^{:stratum 0} guardrail-abort opsv/guardrail-abort)

(def ^{:stratum 0} convergence-iteration opsv/convergence-iteration)

(def ^{:stratum 0} policy-proposed opsv/policy-proposed)

(def ^{:stratum 0} verification-result opsv/verification-result)

(def ^{:stratum 0} actuation-emitted opsv/actuation-emitted)

(def ^{:stratum 0} drift-detected opsv/drift-detected)
