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
(ns ai.miniforge.agent-environment.interface
  "API surface class 1: the versioned agent-environment contract. A
   controller owns the lease ledger and drives any provider through one
   lifecycle; every call emits a receipt marked `:profile/unqualified`.
   Invalid inputs return an `:invalid-input` anomaly without a receipt."
  (:require
   [ai.miniforge.agent-environment.conformance :as conformance]
   [ai.miniforge.agent-environment.controller :as controller]
   [ai.miniforge.agent-environment.fake :as fake]
   [ai.miniforge.agent-environment.lifecycle :as lifecycle]
   [ai.miniforge.agent-environment.schema :as schema]
   [ai.miniforge.agent-environment.validation :as v]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} contract-version
  "The revision every request and receipt names; any other is refused."
  schema/contract-version)

(def ^{:stratum 0} isolation-levels
  "Kernel boundaries a request may require; none is ever substituted."
  schema/isolation-levels)

(def ^{:stratum 0} Request "What a run asks for." schema/Request)

(def ^{:stratum 0} Environment "An acquired environment and its current lease." schema/Environment)

(def ^{:stratum 0} Lease "The fencing token every call after acquire presents." schema/Lease)

(def ^{:stratum 0} Receipt "One lifecycle call's record, refusals included." schema/Receipt)

(def ^{:stratum 0} Capabilities "What a provider declares it can supply." schema/Capabilities)

(def ^{:stratum 0} Command "One command to run inside an environment." schema/Command)

(defn ^{:stratum 0} create-controller
  "A controller over `:provider` with an empty in-memory ledger. `:clock`
   returns the current Instant, `:emit!` receives every receipt, and
   `:persist!` stores one artifact `(persist! lease path text)` outside
   the environment, returning its reference or an anomaly."
  [opts]
  (or (v/check v/ControllerOptions opts) (controller/create opts)))

(defn ^{:stratum 0} preflight
  "Whether the provider can supply `request` now: `{:preflight/failures
   [reason]}`, empty when it can. Checks identity, not the grant."
  [c acting request]
  (or (v/check v/Acting acting schema/Request request) (lifecycle/preflight c acting request)))

(defn ^{:stratum 0} acquire!
  "The environment for `request`, or a refusal or failure anomaly. The same
   request again returns the same environment; a different request for the
   same run and task is refused. `grant` must be a live root
   `:effect/provision` grant issued to the acting principal whose scope
   covers the request's tenant, run and task. A provision that stops part
   way is cleaned up and receipted, and returns `provisioning-failed`."
  [c acting grant request]
  (or (v/check v/Acting acting v/MaybeGrant grant schema/Request request)
      (lifecycle/acquire! c acting grant request)))

(defn ^{:stratum 0} readiness
  "`{:readiness/ready? bool}` for the leased environment; refused when its
   namespace now carries another UID."
  [c acting lease]
  (or (v/check v/Acting acting schema/Lease lease) (lifecycle/readiness c acting lease)))

(defn ^{:stratum 0} exec!
  "Run `command` in the leased environment: `{:exec/exit :exec/stdout :exec/stderr}`."
  [c acting lease command]
  (or (v/check v/Acting acting schema/Lease lease schema/Command command)
      (lifecycle/exec! c acting lease command)))

(defn ^{:stratum 0} write-file!
  "Write UTF-8 `text` at absolute `path` inside the leased environment."
  [c acting lease path text]
  (or (v/check v/Acting acting schema/Lease lease schema/AbsolutePath path :string text)
      (lifecycle/write-file! c acting lease path text)))

(defn ^{:stratum 0} read-file
  "The UTF-8 text at `path` inside the leased environment, or an anomaly."
  [c acting lease path]
  (or (v/check v/Acting acting schema/Lease lease schema/AbsolutePath path)
      (lifecycle/read-file c acting lease path)))

(defn ^{:stratum 0} persist-artifacts!
  "Copy each path out of the environment to the controller's artifact store;
   returns the store's references in path order, or the first anomaly."
  [c acting lease paths]
  (or (v/check v/Acting acting schema/Lease lease [:vector schema/AbsolutePath] paths)
      (lifecycle/persist-artifacts! c acting lease paths)))

(defn ^{:stratum 0} cancel!
  "Stop admitting new work and ask the provider to stop running work.
   Returns `{:call/outcome o}`: what the provider confirmed."
  [c acting lease]
  (or (v/check v/Acting acting schema/Lease lease) (lifecycle/cancel! c acting lease)))

(defn ^{:stratum 0} release!
  "Deprovision the environment. `{:call/outcome :outcome/applied}` only when
   the provider confirmed every delete; otherwise the ledger keeps it as
   cleanup-uncertain and a later release retries."
  [c acting lease]
  (or (v/check v/Acting acting schema/Lease lease) (lifecycle/release! c acting lease)))

(defn ^{:stratum 0} fake-provider
  "An in-memory provider; `ai.miniforge.agent-environment.fake` documents
   the fault options."
  ([] (fake/create {}))
  ([opts] (fake/create opts)))

(defn ^{:stratum 0} fake-commands
  "Every command a fake provider ran in `namespace`, in order."
  [provider namespace]
  (fake/commands provider namespace))

(defn ^{:stratum 0} run-conformance
  "Run the contract suite. `:make-provider` returns a fresh provider; the
   optional `:make-partial-provider` returns one whose provision fails after
   its first step, and `:await-ready` wraps a readiness thunk until ready.
   Returns one `{:check/id :check/outcome :check/detail}` per check."
  [opts]
  (conformance/run opts))
