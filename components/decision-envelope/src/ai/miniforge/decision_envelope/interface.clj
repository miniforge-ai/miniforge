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
(ns ai.miniforge.decision-envelope.interface
  "Public API for the decision-envelope component (Ariadne step 1b):
   the one runtime-constructed truth artifact for policy decisions.
   The factory derives the decision from reasons + obligations — no
   public path accepts a caller-supplied decision (§13.4: the label
   plane is model-unwritable)."
  (:require
   [ai.miniforge.decision-envelope.core :as core]
   [ai.miniforge.decision-envelope.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} decisions
  "Envelope decisions, most to least restrictive."
  schema/decisions)

(def ^{:stratum 0} reason-codes
  "Registered reason codes."
  schema/reason-codes)

(def ^{:stratum 0} obligation-types
  "Registered obligation types."
  schema/obligation-types)

(def ^{:stratum 0} DecisionEnvelope
  "Closed Malli schema for an envelope."
  schema/DecisionEnvelope)

(def ^{:stratum 0} envelope
  "Mint an envelope from reasons, obligations, and pins; the decision
   is derived worst-wins, never supplied."
  core/envelope)

(def ^{:stratum 0} derive-decision
  "The worst-wins derivation (exposed for table-driven tests)."
  core/derive-decision)

(def ^{:stratum 0} valid?
  "Validate a value against the closed envelope schema."
  core/valid?)
