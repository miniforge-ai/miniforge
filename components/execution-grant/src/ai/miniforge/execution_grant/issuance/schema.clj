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
(ns ai.miniforge.execution-grant.issuance.schema
  "Closed runtime inputs for irreversible-effect grant issuance.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} NonBlankString
  "A scope binding that cannot silently collapse to an empty value."
  [:string {:min 1}])

(defn- ^{:stratum 0} request-schema
  "Build one closed request without duplicating authority or basis fields."
  [effect-class preflight-type target-fields]
  (into [:map {:closed true}
         [:workflow-run/id :uuid]
         [:workflow-run/status [:= :running]]
         [:effect/id :uuid]
         [:effect/class [:= effect-class]]
         [:effect/preflight
          [:map {:closed true}
           [:preflight/type [:= preflight-type]]
           [:preflight/result [:enum :allow :deny]]]]]
        target-fields))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} MergeRequest
  "Closed request for one exact pull-request merge."
  (request-schema
   :effect/merge
   :preflight/merge-readiness
   [[:pr/repo NonBlankString]
    [:pr/number pos-int?]]))

(def ^{:stratum 1} DeployRequest
  "Closed request for one exact Kubernetes deployment.

   `:context` may be nil only because the issuer replaces it with the
   required configured default before constructing grant scope."
  (request-schema
   :effect/deploy
   :preflight/deploy-policy-and-dry-run
   [[:kustomize-dir NonBlankString]
    [:context [:maybe NonBlankString]]
    [:default-context NonBlankString]
    [:namespace NonBlankString]
    [:deployment-name NonBlankString]]))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Arguments
  "Canonical effect request plus the runtime-owned issuance instant."
  [:tuple
   [:multi {:dispatch :effect/class}
    [:effect/merge MergeRequest]
    [:effect/deploy DeployRequest]]
   inst?])
