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
(ns ai.miniforge.agent-environment.schema
  "Versioned values of the agent-environment contract: what a run asks for,
   the environment and lease it holds, and the receipt each lifecycle step
   leaves. Every map is closed: an unknown key is a contract change."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} contract-version
  "The contract revision every request and receipt names. A value naming
   another revision is refused, never reinterpreted."
  "agent-environment/v1")

(def ^{:stratum 0} isolation-levels
  "Kernel boundary a request requires. A provider that cannot supply the
   requested level fails preflight; it never substitutes a weaker one."
  [:isolation/shared-kernel :isolation/dedicated-kernel])

(def ^{:stratum 0} profiles
  "Qualification profile on receipts. Only `:profile/unqualified` exists
   until real-runtime qualification rows pass."
  [:profile/unqualified])

(def ^{:stratum 0} operations
  "The lifecycle operations a receipt can record."
  [:environment/preflight :environment/acquire :environment/readiness
   :environment/exec :environment/copy :environment/persist-artifacts
   :environment/cancel :environment/release])

(def ^{:stratum 0} outcomes
  "`:outcome/uncertain` means requested but not verified, never applied."
  [:outcome/applied :outcome/duplicate :outcome/refused
   :outcome/partial :outcome/failed :outcome/uncertain])

(def ^{:stratum 0} step-outcomes
  "What one provider step reports."
  [:outcome/applied :outcome/failed :outcome/uncertain])

(def ^{:stratum 0} NonBlank
  [:and :string [:fn (complement str/blank?)]])

(def ^{:stratum 0} Sha256Hex
  "Lowercase hex SHA-256, as `content-hash` produces."
  [:re #"^[0-9a-f]{64}$"])

(def ^{:stratum 0} NamespaceName
  "A DNS-1123 label: the strictest naming rule a provider namespace meets."
  [:re #"^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$"])

(def ^{:stratum 0} Limits
  [:map {:closed true}
   [:limits/cpu-millicores pos-int?]
   [:limits/memory-mib pos-int?]
   [:limits/ephemeral-storage-mib pos-int?]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Step
  [:map {:closed true}
   [:step/name :keyword]
   [:step/outcome (into [:enum] step-outcomes)]])

(def ^{:stratum 1} Command
  "One command; `:exec/stdin` is piped to it when present. The executable
   must be named; later arguments may be empty or whitespace."
  [:map {:closed true}
   [:exec/argv [:and vector? [:cat NonBlank [:* :string]]]]
   [:exec/stdin {:optional true} :string]
   [:exec/timeout-ms pos-int?]])

(def ^{:stratum 1} Request
  "What a run asks for. Tenant and principal must be the submitter's acting
   context, and a provision grant must cover the run and task."
  [:map {:closed true}
   [:environment/contract-version [:= contract-version]]
   [:environment/tenant-id :uuid]
   [:environment/principal-id :uuid]
   [:environment/run-id :uuid]
   [:environment/task-id :uuid]
   [:environment/template-hash Sha256Hex]
   [:environment/policy-hash Sha256Hex]
   [:environment/isolation (into [:enum] isolation-levels)]
   [:environment/limits Limits]
   [:environment/expires-at inst?]])

(def ^{:stratum 1} Lease
  "The fencing token every call after acquire presents. A lease whose UID
   or generation is not the ledger's current one is refused."
  [:map {:closed true}
   [:lease/tenant-id :uuid]
   [:lease/run-id :uuid]
   [:lease/task-id :uuid]
   [:lease/environment-uid NonBlank]
   [:lease/generation pos-int?]])

(def ^{:stratum 1} Capabilities
  "What a provider declares it can supply. Preflight compares every request
   against it before the provider's live probe runs."
  [:map {:closed true}
   [:capabilities/provider :keyword]
   [:capabilities/isolation [:set (into [:enum] isolation-levels)]]
   [:capabilities/max-limits Limits]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Receipt
  "One lifecycle call's record, refusals included. A nil environment UID
   means nothing was provisioned for this call."
  [:map {:closed true}
   [:receipt/id :uuid]
   [:receipt/contract-version [:= contract-version]]
   [:receipt/profile (into [:enum] profiles)]
   [:receipt/operation (into [:enum] operations)]
   [:receipt/outcome (into [:enum] outcomes)]
   [:receipt/tenant-id :uuid]
   [:receipt/principal-id :uuid]
   [:receipt/run-id :uuid]
   [:receipt/task-id :uuid]
   [:receipt/environment-uid [:maybe NonBlank]]
   [:receipt/lease-generation [:maybe pos-int?]]
   [:receipt/steps [:vector Step]]
   [:receipt/reason [:maybe :keyword]]
   [:receipt/at inst?]])

(def ^{:stratum 2} Environment
  "An acquired environment: the request exactly as admitted, the UID its
   provider assigned, the namespace derived from its run and task, and the
   current lease."
  [:map {:closed true}
   [:environment/request Request]
   [:environment/uid NonBlank]
   [:environment/namespace NamespaceName]
   [:environment/lease Lease]])
