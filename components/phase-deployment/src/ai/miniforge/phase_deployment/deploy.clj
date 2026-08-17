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
(ns ai.miniforge.phase-deployment.deploy
  "Thin deploy phase interceptor over provider application flow."
  (:import
   [java.time Instant])
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase-deployment.defaults :as defaults]
            [ai.miniforge.phase-deployment.deploy-config :as config]
            [ai.miniforge.phase-deployment.deploy-governed :as governed]
   [ai.miniforge.phase-deployment.deploy-operations :as operations]
            [ai.miniforge.phase-deployment.deploy-result :as result]
            [ai.miniforge.phase-deployment.messages :as msg]
            [ai.miniforge.phase.interface :as phase]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-config
  "Deploy phase defaults loaded from EDN."
  (defaults/phase-defaults :deploy))

(defn- ^{:stratum 0} get-logger
  "Resolve logger from ctx, creating a default if absent."
  [ctx]
  (or (get-in ctx [:execution/logger])
      (log/create-logger {:min-level :info :output :human})))

(defn- ^{:stratum 0} invalid-config-result
  [ctx start-time error]
  (-> ctx
      (assoc-in [:phase :name] :deploy)
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :started-at] start-time)
      (assoc-in [:phase :result]
                {:status :error
                 :error (msg/t :deploy/invalid-config {:error error})})))

(defn ^{:stratum 0} leave-deploy
  "Post-deploy: record final metrics."
  [ctx]
  (if (= :completed (get-in ctx [:phase :status]))
    ctx
    (assoc-in ctx [:phase :status] :failed)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} error-deploy
  "Handle deploy phase errors."
  [ctx ex]
  (let [logger (get-logger ctx)]
    (log/error logger :deploy :deploy/error
               {:data {:message (ex-message ex)
                       :data    (ex-data ex)}})
    (-> ctx
        (assoc-in [:phase :status] :failed)
        (update :execution/errors (fnil conj [])
                {:type    :deploy-error
                 :phase   :deploy
                 :message (ex-message ex)
                 :data    (ex-data ex)}))))

(defn ^{:stratum 1} enter-deploy
  "Resolve one target and execute its deployment application flow."
  [ctx]
  (let [start-time (System/currentTimeMillis)
        logger     (get-logger ctx)]
    (try
      (let [deploy-config (config/resolve-config ctx)]
        (if (anomaly/anomaly? deploy-config)
          (invalid-config-result ctx start-time
                                 (:anomaly/message deploy-config))
          ;; Governed path: dry-run, policy-check, authorize, record
          ;; durably, and only then mutate. `flow/execute!` applied
          ;; straight through with no gate at all, while
          ;; check-resource-count and check-gke-node-limit sat uncalled.
          (result/store-deployment ctx start-time logger deploy-config
                                   (governed/transact! ctx deploy-config
                                                       (operations/operations)
                                                       (Instant/now)))))
      (catch clojure.lang.ExceptionInfo ex
        (invalid-config-result ctx start-time (ex-message ex))))))

;------------------------------------------------------------------------------ Layer 2

(defmethod ^{:stratum 2} phase/get-phase-interceptor-method :deploy
  [_]
  {:name :deploy
   :enter enter-deploy
   :leave leave-deploy
   :error error-deploy
   :config default-config})

;------------------------------------------------------------------------------ Rich Comment
(comment
  (enter-deploy {:execution/input {:kustomize-dir "/path/to/overlay"
                                   :namespace "ixi"
                                   :app-label "ixi"}})
  :leave-this-here)
