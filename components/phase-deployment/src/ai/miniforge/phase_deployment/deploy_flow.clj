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
(ns ai.miniforge.phase-deployment.deploy-flow
  "Application flow for deployment provider operations."
  (:require
   [ai.miniforge.phase-deployment.deploy-provider :as provider]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} outcome
  [status stage rollback-info data]
  (merge {:deploy/status status :deploy/stage stage
          :deploy/rollback-info rollback-info}
         data))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} apply-outcome!
  [deploy-config rollback-info]
  (let [applied (provider/apply! deploy-config)
        rendered-yaml (or (:rendered-yaml applied)
                          (get-in applied [:build-result :stdout]))]
    (if (schema/failed? applied)
      (outcome :failed :apply rollback-info
               {:deploy/rendered-yaml rendered-yaml
                :deploy/failure (:error applied)})
      (let [observation (provider/observe! deploy-config)
            observed (:provider/observed observation)
            matched? (:provider/matched? observation)
            base-data {:deploy/rendered-yaml rendered-yaml
                       :deploy/pod-state (:deployment/pods observed)}
            data (if matched? base-data
                     (assoc base-data :deploy/failure (get observed :deployment/failure)))]
        (outcome (if matched? :success :failed)
                 :observe rollback-info data)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute!
  "Capture rollback state, apply, and translate provider observation."
  [deploy-config]
  (let [result (provider/rollback-info! deploy-config)]
    (if (schema/failed? result)
      (outcome :failed :capture nil {:deploy/failure (:error result)})
      (apply-outcome! deploy-config (:rollback-info result)))))
