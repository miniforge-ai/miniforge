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

   Nothing consumes these yet — 3b threads the identity through the run
   hierarchy and 3c stamps it onto records. This slice is the vocabulary
   and its source."
  (:require
   [ai.miniforge.tenancy.resolver :as resolver]
   [ai.miniforge.tenancy.schema :as schema]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} tenant-kinds schema/tenant-kinds)

(def ^{:stratum 0} principal-kinds schema/principal-kinds)

(def ^{:stratum 0} Tenant schema/Tenant)

(def ^{:stratum 0} Principal schema/Principal)

(def ^{:stratum 0} Identity schema/Identity)

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
