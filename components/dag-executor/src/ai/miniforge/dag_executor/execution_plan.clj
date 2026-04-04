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

(ns ai.miniforge.dag-executor.execution-plan
  "Execution plan data structure for sandboxed task isolation.

   Captures everything needed to launch a task in an isolated container:
   image identity, command, filesystem mounts, environment variables,
   secret references, network profile, resource limits, and trust level.

   Layer 0: Constants — trust levels and network profiles
   Layer 1: Malli schemas — mount, execution plan
   Layer 2: Constructor and validator"
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0
;; Trust levels and network profiles

(def trust-levels
  "Valid trust levels for an execution plan.

   - :untrusted   External / community PRs. Maximum sandbox restrictions.
   - :trusted     Internal PRs from known contributors. Standard restrictions.
   - :privileged  Admin or CI pipelines. May access secrets and full network."
  #{:untrusted :trusted :privileged})

(def network-profiles
  "Valid network profiles for an execution plan.

   - :none        Air-gapped. No outbound or inbound network access.
   - :restricted  Allow-list only. Egress limited to an explicit set of hosts.
   - :standard    Default-deny egress with exceptions for common registries / APIs.
   - :full        Unrestricted network access (privileged tasks only)."
  #{:none :restricted :standard :full})

;------------------------------------------------------------------------------ Layer 1
;; Malli schemas

(def MountSchema
  "Schema for a filesystem mount binding a host path into the container."
  [:map
   [:host-path      :string]
   [:container-path :string]
   [:read-only?     :boolean]])

(def ExecutionPlanSchema
  "Schema for a fully-specified execution plan.

   Fields:
   - :image-digest      Content-addressed container image (sha256:...).
   - :command           Argv vector, e.g. [\"bb\" \"run\" \"task\"].
   - :mounts            Vector of mount maps (see MountSchema).
   - :env               String->string environment variable map.
   - :secrets-refs      Vector of secret reference identifiers (strings).
   - :network-profile   One of the network-profiles keywords.
   - :time-limit-ms     Wall-clock timeout in milliseconds.
   - :memory-limit-mb   RSS memory ceiling in mebibytes.
   - :trust-level       One of the trust-levels keywords."
  [:map
   [:image-digest    :string]
   [:command         [:vector :string]]
   [:mounts          [:vector MountSchema]]
   [:env             [:map-of :string :string]]
   [:secrets-refs    [:vector :string]]
   [:network-profile (into [:enum] network-profiles)]
   [:time-limit-ms   :int]
   [:memory-limit-mb :int]
   [:trust-level     (into [:enum] trust-levels)]])

;------------------------------------------------------------------------------ Layer 2
;; Constructor and validator

(defn validate-plan
  "Validate a map against the ExecutionPlanSchema.

   Arguments:
   - plan: Map to validate

   Returns {:valid? true} when the plan is valid, or
           {:valid? false :errors [...]} with humanised Malli error messages."
  [plan]
  (if (m/validate ExecutionPlanSchema plan)
    {:valid? true}
    {:valid?  false
     :errors  (-> ExecutionPlanSchema
                  (m/explain plan)
                  me/humanize)}))

(defn create-execution-plan
  "Construct and validate an execution plan.

   Arguments:
   - plan-map: Map conforming to ExecutionPlanSchema.

   Returns the validated plan map unchanged if valid.
   Throws ex-info with :errors key if validation fails."
  [plan-map]
  (let [{:keys [valid? errors]} (validate-plan plan-map)]
    (if valid?
      plan-map
      (throw (ex-info "Invalid execution plan" {:errors errors
                                                :plan   plan-map})))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; -------------------------------------------------------------------------
  ;; Schema exploration
  ;; -------------------------------------------------------------------------

  trust-levels
  ;; => #{:untrusted :trusted :privileged}

  network-profiles
  ;; => #{:none :restricted :standard :full}

  ;; -------------------------------------------------------------------------
  ;; Validation
  ;; -------------------------------------------------------------------------

  (def good-plan
    {:image-digest    "sha256:abc123"
     :command         ["bb" "run" "test"]
     :mounts          [{:host-path "/workspace" :container-path "/work" :read-only? false}]
     :env             {"HOME" "/root" "CI" "true"}
     :secrets-refs    ["gh-token"]
     :network-profile :standard
     :time-limit-ms   60000
     :memory-limit-mb 512
     :trust-level     :trusted})

  (validate-plan good-plan)
  ;; => {:valid? true}

  (validate-plan (dissoc good-plan :image-digest))
  ;; => {:valid? false :errors {...}}

  ;; -------------------------------------------------------------------------
  ;; Constructor
  ;; -------------------------------------------------------------------------

  (create-execution-plan good-plan)
  ;; => good-plan (unchanged)

  (create-execution-plan {:image-digest "sha256:abc" :trust-level :unknown})
  ;; throws ExceptionInfo with :errors

  :leave-this-here)
