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
(ns ai.miniforge.phase-deployment.deploy-result
  "Phase-context and evidence projection for deployment outcomes."
  (:require
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.phase-deployment.evidence :as evidence]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} phase-context
  [ctx start-time status result]
  (-> ctx
      (assoc-in [:phase :name] :deploy)
      (assoc-in [:phase :status] status)
      (assoc-in [:phase :started-at] start-time)
      (assoc-in [:phase :result] result)))

(defn- ^{:stratum 0} evidence-items
  [deploy-config deployment]
  (let [rollback-info (:deploy/rollback-info deployment)
        rendered (:deploy/rendered-yaml deployment)
        images (:deploy/images deployment)]
    (cond-> []
      rollback-info
      (conj (evidence/create-evidence
             :evidence/rollback-info rollback-info
             {:deployment (:deployment-name deploy-config)
              :namespace (:namespace deploy-config)}))
      rendered
      (conj (evidence/create-evidence
             :evidence/rendered-manifests rendered
             {:kustomize-dir (:kustomize-dir deploy-config)}))
      rendered
      (conj (evidence/create-evidence :evidence/image-digests images)))))

(defn- ^{:stratum 0} add-evidence
  [ctx items]
  (reduce evidence/add-evidence-to-ctx ctx items))

(def ^{:stratum 0 :private true} failure-details
  {:capture {:event :deploy/rollback-capture-failed :status :error}
   :apply {:event :deploy/apply-failed :status :error}
   :observe {:event :deploy/rollout-failed :status :rollout-failed}})

(defn- ^{:stratum 0} log-applied
  [logger deployment]
  (when (= :observe (:deploy/stage deployment))
    (log/info logger :deploy :deploy/applied
              {:data {:image-count (count (:deploy/images deployment))}})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} store-success
  [ctx start-time logger deploy-config deployment]
  (let [pod-state (:deploy/pod-state deployment)
        images (:deploy/images deployment)
        metrics {:duration-ms (- (System/currentTimeMillis) start-time)
                 :pod-count (:pod-count pod-state)
                 :ready-count (:ready-count pod-state)}]
    (log/info logger :deploy :deploy/complete {:data metrics})
    (-> (phase-context
         ctx start-time :completed
         {:status :success
          :output {:pod-state pod-state :images images}
          :artifact {:content pod-state
                     :type :deployment-state
                     :app-label (:app-label deploy-config)
                     :namespace (:namespace deploy-config)}
          :metrics metrics})
        (assoc-in [:phase :gates] (get-in deploy-config [:phase-config :gates]))
        (assoc-in [:phase :budget] (get-in deploy-config [:phase-config :budget]))
        (add-evidence (evidence-items deploy-config deployment)))))

(defn- ^{:stratum 1} store-failure
  [ctx start-time logger deploy-config deployment]
  (let [{:keys [event status]}
        (get failure-details (:deploy/stage deployment)
             (get failure-details :apply))]
    (log/error logger :deploy event
               {:data {:stage (:deploy/stage deployment)
                       :error (:deploy/failure deployment)}})
    (-> (phase-context
         ctx start-time :failed
         {:status status
          :error (:deploy/failure deployment)
          :stage (:deploy/stage deployment)
          :metrics {:duration-ms (- (System/currentTimeMillis) start-time)}})
        (add-evidence (evidence-items deploy-config deployment)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} store-deployment
  [ctx start-time logger deploy-config deployment]
  (let [images (some-> (:deploy/rendered-yaml deployment)
                       evidence/extract-image-digests)
        deployment (assoc deployment :deploy/images images)]
    (log-applied logger deployment)
    (if (= :success (:deploy/status deployment))
      (store-success ctx start-time logger deploy-config deployment)
      (store-failure ctx start-time logger deploy-config deployment))))
