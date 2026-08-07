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
(ns ai.miniforge.phase-deployment.deploy-registration
  "Phase registry composition for the deployment application flow."
  (:require
   [ai.miniforge.phase-deployment.deploy :as deploy]
   [ai.miniforge.phase.interface :as phase]))

;------------------------------------------------------------------------------ Layer 0

(defmethod ^{:stratum 0} phase/get-phase-interceptor-method :deploy
  [_]
  {:name :deploy
   :enter deploy/enter-deploy
   :leave deploy/leave-deploy
   :error deploy/error-deploy
   :config deploy/default-config})
