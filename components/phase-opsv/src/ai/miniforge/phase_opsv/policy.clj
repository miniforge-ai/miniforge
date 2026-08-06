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
(ns ai.miniforge.phase-opsv.policy
  "Construct an operational policy proposal from converged OPSV evidence.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} operational-policy
  [ctx convergence]
  (let [pack (get-in ctx [:execution/input :opsv/experiment-pack])
        targets (:experiment-pack/targets pack)
        evidence-refs (get-in ctx [:execution/input :opsv/evidence-refs])
        selected-step (get-in convergence [:state :selected-step])
        metrics (:step/metrics selected-step)]
    {:operational-policy/id (str (:experiment-pack/id pack) "-policy")
     :operational-policy/version "1.0.0"
     :operational-policy/target-services (:services targets)
     :operational-policy/target-envs (:environments targets)
     :operational-policy/scaling
     {:hpa {:api-version "autoscaling/v2"
            :metric :cpu
            :target-utilization (:hpa-target-utilization metrics)
            :min-replicas (:min-replicas metrics)
            :max-replicas (:max-replicas metrics)}
      :keda {:trigger :backlog
             :target (:keda-backlog-target metrics)}}
     :operational-policy/resources
     {:cpu-request-millicores (:cpu-request-millicores metrics)}
     :operational-policy/guardrails (:experiment-pack/guardrails pack)
     :operational-policy/verification-summary
     {:passed? false :confidence :pending :caveats []}
     :operational-policy/rollback-plan {:strategy :restore-previous-policy}
     :operational-policy/evidence-refs evidence-refs}))
