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
   returns the current Instant and `:emit!` receives every receipt."
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
