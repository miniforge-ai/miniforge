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
(ns ai.miniforge.tenancy.schema
  "Tenant and Principal (Ariadne step 3a, §2-§4).

   A TENANT owns. A PRINCIPAL acts. Keeping those separate is the whole
   design: an agent instance acts constantly and owns nothing, and a
   customer org owns a great deal while never touching a keyboard.
   Collapsing them would make 'who owns this artifact' and 'who did
   this' the same question, and they have different answers whenever
   more than one operator exists.

   Both schemas are CLOSED, following DecisionEnvelope and
   ExecutionGrant. An identity nobody can describe is an identity nobody
   can audit."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} tenant-kinds
  "Who can own.

   `:operator`      — the human or org running miniforge.
   `:customer-org`  — an org whose code is being worked on; owns its
                      domain outputs even though it never launched a run.
   `:service`       — a non-human owner for system-generated artifacts
                      that belong to no person."
  [:operator :customer-org :service])

(def ^{:stratum 0} principal-kinds
  "Who can act.

   `:agent-instance` is deliberately a principal and never a tenant: an
   agent acts under authority lent to it and owns nothing it produces.
   That asymmetry is what makes a spawned agent containable — revoke the
   lending tenant's grant and the agent's authority ends with it."
  [:human :agent-instance :service])

(def ^{:stratum 0} NonBlank
  [:and :string [:fn (complement str/blank?)]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Tenant
  "CLOSED. The unit that owns."
  [:map {:closed true}
   [:tenant/id :uuid]
   [:tenant/kind (into [:enum] tenant-kinds)]
   [:tenant/display-name NonBlank]
   [:tenant/created-at inst?]])

(def ^{:stratum 1} Principal
  "CLOSED. The unit that acts, always belonging to exactly one tenant.

   `:principal/tenant-id` is required and non-nullable. A principal
   without a tenant is an actor nobody can attribute, which is the
   state this component exists to make unrepresentable."
  [:map {:closed true}
   [:principal/id :uuid]
   [:principal/tenant-id :uuid]
   [:principal/kind (into [:enum] principal-kinds)]
   [:principal/display-name NonBlank]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Identity
  "What a boundary resolves: exactly one tenant and one principal, and
   the principal belongs to that tenant.

   Returned as a pair rather than a bare tenant id because 'the operator
   asked' and 'an agent instance did it' stamp differently, and a
   caller holding only a tenant cannot tell them apart."
  [:map {:closed true}
   [:identity/tenant Tenant]
   [:identity/principal Principal]])
