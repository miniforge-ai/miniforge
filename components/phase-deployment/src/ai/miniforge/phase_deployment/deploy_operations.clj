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
(ns ai.miniforge.phase-deployment.deploy-operations
  "Injectable Kubernetes operations for the governed deploy transaction."
  (:require
   [ai.miniforge.phase-deployment.deploy-provider :as provider]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} operations
  "Return the provider boundary used by the governed transaction.

   Rendered bytes remain ordinary transaction data. The durable proposal,
   rather than adapter state, is the source used by commit and reconciliation."
  []
  {:target! provider/target!
   :render! provider/render!
   :dry-run! provider/dry-run!
   :rollback-info! provider/rollback-info!
   :apply! provider/apply-rendered!
   :observe! provider/observe!})
