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
(ns ai.miniforge.agent-worker.interface
  "API surface class 1: the versioned agent-worker contract. Miniforge owns
   the run, the environment and authority; a worker owns its own agent loop
   inside them. Every call is bound to run, task, environment UID, lease
   generation, spend grant, config digest and attempt. An explicitly
   selected worker that is missing or fails preflight is an error; no other
   worker is substituted. Unknown usage is nil, never zero."
  (:require
   [ai.miniforge.agent-worker.controller :as controller]
   [ai.miniforge.agent-worker.fake :as fake]
   [ai.miniforge.agent-worker.lifecycle :as lifecycle]
   [ai.miniforge.agent-worker.schema :as schema]
   [ai.miniforge.agent-worker.session :as session]
   [ai.miniforge.agent-worker.validation :as v]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} contract-version
  "The revision every binding names; any other is refused."
  schema/contract-version)

(def ^{:stratum 0} Binding "What every worker call is bound to." schema/Binding)

(def ^{:stratum 0} Config "The explicitly selected worker and its options." schema/Config)

(def ^{:stratum 0} Submission "One attempt: binding, config and prompt." schema/Submission)

(def ^{:stratum 0} Session "A started attempt and its native references." schema/Session)

(def ^{:stratum 0} Status "What inspection reports." schema/Status)

(def ^{:stratum 0} Usage "Reported usage; unknown quantities are nil." schema/Usage)

(def ^{:stratum 0} Capabilities "What a worker declares it can do." schema/Capabilities)

(defn ^{:stratum 0} bind
  "The binding for attempt `attempt` of `config` in the leased environment
   under spend grant `grant`."
  [acting lease grant config attempt]
  (or (v/check v/Acting acting v/Lease lease v/Grant grant schema/Config config pos-int? attempt)
      (session/bind acting lease grant config attempt)))

(defn ^{:stratum 0} create-controller
  "A worker controller over `:workers` (selection keyword to worker), the
   agent-environment controller `:environment`, and `:clock`."
  [opts]
  (or (v/check v/ControllerOptions opts) (controller/create opts)))

(defn ^{:stratum 0} start!
  "Start `submission` and return its session, or a refusal. The submission
   is recorded before the worker starts: the identical submission again
   returns the recorded session (or start failure) without relaunching, and
   different content for the same attempt is refused."
  [wc acting grant lease submission]
  (or (v/check v/Acting acting v/MaybeGrant grant v/Lease lease schema/Submission submission)
      (lifecycle/start! wc acting grant lease submission)))

(defn ^{:stratum 0} inspect
  "The session's `Status`. A completed session is evidence, not acceptance."
  [wc acting session]
  (or (v/check v/Acting acting schema/Session session) (lifecycle/inspect wc acting session)))

(defn ^{:stratum 0} send-input!
  "Steer a session between turns: the next turn's session, or a refusal
   (`:unsupported` for a worker without steering). The spend grant is
   checked again, since each turn spends."
  [wc acting grant session text]
  (or (v/check v/Acting acting v/MaybeGrant grant schema/Session session schema/NonBlank text)
      (lifecycle/send-input! wc acting grant session text)))

(defn ^{:stratum 0} request-cancel!
  "Ask the running turn to stop: `{:cancel/requested? bool}`. Requested is
   not applied; `inspect` reports what happened."
  [wc acting session]
  (or (v/check v/Acting acting schema/Session session) (lifecycle/request-cancel! wc acting session)))

(defn ^{:stratum 0} persist-artifacts!
  "Persist what the session wrote through the environment's artifact store."
  [wc acting session]
  (or (v/check v/Acting acting schema/Session session) (lifecycle/persist-artifacts! wc acting session)))

(defn ^{:stratum 0} usage
  "The session's `Usage`; a quantity the worker did not report is nil."
  [wc acting session]
  (or (v/check v/Acting acting schema/Session session) (lifecycle/usage wc acting session)))

(defn ^{:stratum 0} fake-worker
  "An in-memory worker; `ai.miniforge.agent-worker.fake` documents its options."
  ([] (fake/create {}))
  ([opts] (fake/create opts)))
