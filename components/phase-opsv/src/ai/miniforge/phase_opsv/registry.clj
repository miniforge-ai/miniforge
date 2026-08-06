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
(ns ai.miniforge.phase-opsv.registry
  "Shared phase-registry wiring for the deterministic OPSV pipeline."
  (:require
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-opsv.interface :as opsv]
   [ai.miniforge.phase-opsv.lifecycle :as lifecycle]))

;------------------------------------------------------------------------------ Layer 0

(defmethod ^{:stratum 0} phase/get-phase-interceptor-method :opsv/discover
  [config]
  (lifecycle/interceptor config :opsv/discover opsv/discover))

(defmethod ^{:stratum 0} phase/get-phase-interceptor-method :opsv/plan
  [config]
  (lifecycle/interceptor config :opsv/plan opsv/plan))

(defmethod ^{:stratum 0} phase/get-phase-interceptor-method :opsv/execute
  [config]
  (lifecycle/interceptor config :opsv/execute opsv/execute))

(defmethod ^{:stratum 0} phase/get-phase-interceptor-method :opsv/converge
  [config]
  (lifecycle/interceptor config :opsv/converge opsv/converge))

(defmethod ^{:stratum 0} phase/get-phase-interceptor-method :opsv/synthesize
  [config]
  (lifecycle/interceptor config :opsv/synthesize opsv/synthesize))

(defmethod ^{:stratum 0} phase/get-phase-interceptor-method :opsv/verify
  [config]
  (lifecycle/interceptor config :opsv/verify opsv/verify))

(defmethod ^{:stratum 0} phase/get-phase-interceptor-method :opsv/actuate
  [config]
  (lifecycle/interceptor config :opsv/actuate opsv/actuate))

(doseq [phase-key opsv/phase-keys]
  (phase/register-phase-defaults! phase-key lifecycle/default-config))
