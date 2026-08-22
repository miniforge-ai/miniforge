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
(ns ai.miniforge.phase-deployment.deploy-outcome
  "Pure projection from governed deploy state to the phase outcome contract."
  (:require
   [ai.miniforge.phase-deployment.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} deployment
  [state status stage data]
  (merge {:deploy/status status :deploy/stage stage
          :deploy/rollback-info (:rollback-info state)
          :deploy/rendered-yaml (:rendered-yaml state)
          :deploy/effect-id (get-in state [:authority :effect/id])
          :deploy/grant-id (get-in state [:authority :authority/grant :grant/id])
          :deploy/envelope-id (get-in state [:authority :authority/envelope :envelope/id])}
         data))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} transaction-deployment
  [state]
  (let [effect (:transaction state)
        observed (:effect/observed effect)
        pod-state (:deployment/pods observed)]
    (cond
      (= :failed (:effect/state effect))
      (deployment state :failed
                  (if (= :granted (:effect/authority effect))
                    :apply
                    :authority)
                  {:deploy/failure (:effect/failure effect)})

      (and (= :reconciled (:effect/state effect))
           (:effect/matched? effect))
      (deployment state :success :observe {:deploy/pod-state pod-state})

      (= :reconciled (:effect/state effect))
      (deployment state :failed :observe
                  {:deploy/pod-state pod-state
                   :deploy/failure (:deployment/failure observed)})

      :else
      (deployment state :failed :observe
                  {:deploy/failure
                   (msg/t :deploy/reconciliation-incomplete)}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} from-state
  "Project one completed or refused governed flow into its phase result."
  [state]
  (if-let [{:keys [stage message]} (:flow/failure state)]
    (deployment state :failed stage {:deploy/failure message})
    (transaction-deployment state)))
