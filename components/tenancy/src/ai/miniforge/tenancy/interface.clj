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
(ns ai.miniforge.tenancy.interface
  "Public API for tenancy (Ariadne step 3a): who owns, who acts, and the
   one boundary that establishes an operator.

   3b threads the identity through the run hierarchy; 3c stamps it onto
   records. This namespace is the vocabulary, its source, and the acting
   context that carries it."
  (:require
   [ai.miniforge.tenancy.acting :as acting]
   [ai.miniforge.tenancy.resolver :as resolver]
   [ai.miniforge.tenancy.schema :as schema]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} tenant-kinds schema/tenant-kinds)

(def ^{:stratum 0} principal-kinds schema/principal-kinds)

(def ^{:stratum 0} Tenant schema/Tenant)

(def ^{:stratum 0} Principal schema/Principal)

(def ^{:stratum 0} Identity schema/Identity)

(def ^{:stratum 0} invalid-operator-identity
  "Anomaly subtype meaning an operator WAS configured and is wrong, as
   distinct from none being configured. Callers branch on this to tell
   'nobody set this up yet' from 'you set it up wrong'."
  :anomalies.tenancy/invalid-operator-identity)

(def ^{:stratum 0} resolve-operator
  "Resolve the operating identity from configuration, or return an
   anomaly. Never invents a default owner."
  resolver/resolve-operator)

(def ^{:stratum 0} operator-identity
  "Build an operator identity from a name and instant (pure)."
  resolver/operator-identity)

(defn ^{:stratum 0} valid-tenant? [t] (m/validate schema/Tenant t))

(defn ^{:stratum 0} valid-principal? [p] (m/validate schema/Principal p))

(defn ^{:stratum 0} valid-identity? [i] (m/validate schema/Identity i))

(def ^{:stratum 0} ActingContext acting/ActingContext)

(def ^{:stratum 0} establish-acting
  "Reduce a resolved identity to the acting context a run carries.
   Called once per boundary; downstream reads rather than re-resolving."
  acting/establish)

(def ^{:stratum 0} require-acting
  "Return the acting context held at `key`, or an anomaly. Never
   substitutes a default and never returns nil."
  acting/require-acting)

(def ^{:stratum 0} acting-for-agent
  "The acting context a spawned agent runs under: same tenant, its own
   `:agent-instance` principal."
  acting/for-agent)

(def ^{:stratum 0} agent-principal
  "The principal a spawned agent acts as."
  acting/agent-principal)

(defn ^{:stratum 0} valid-acting? [a] (acting/valid? a))
