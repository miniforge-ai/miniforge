;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.phase.release
  "Release phase interceptor.

   Prepares release artifacts and creates PRs.
   Agent: :releaser (simplified - no LLM agent yet)
   Default gates: [:release-ready]"
  (:require [ai.miniforge.phase.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  {:agent :releaser
   :gates [:release-ready]
   :budget {:tokens 5000
            :iterations 2
            :time-seconds 180}})

;; Register defaults on load
(registry/register-phase-defaults! :release default-config)

;; Also register :done as a terminal phase
(registry/register-phase-defaults! :done
                                   {:agent nil
                                    :gates []
                                    :budget {:tokens 0 :iterations 1 :time-seconds 1}})

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn- create-release-artifact
  "Create a simple release artifact from previous phase outputs.
   This is a simplified implementation until a full releaser agent is created."
  [ctx]
  (let [implement-result (get-in ctx [:phases :implement :result])
        verify-result (get-in ctx [:phases :verify :result])
        review-result (get-in ctx [:phases :review :result])
        input (get-in ctx [:execution/input])]
    {:release/id (random-uuid)
     :release/status :ready
     :release/artifacts {:code implement-result
                         :tests verify-result
                         :review review-result}
     :release/title (or (:title input) "Release")
     :release/description (or (:description input) "")
     :release/created-at (java.util.Date.)}))

(defn- enter-release
  "Execute release phase.

   Prepares release artifacts, creates PR if configured."
  [ctx]
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)

        ;; Create release artifact (simplified - no agent yet)
        ;; In the future, this would invoke a releaser agent that:
        ;; - Generates changelog
        ;; - Creates git commits
        ;; - Prepares PR description
        ;; - Handles versioning
        result (try
                 (let [artifact (create-release-artifact ctx)]
                   {:status :success
                    :output artifact
                    :artifact artifact
                    :metrics {:tokens 0
                              :duration-ms 0}})
                 (catch Exception e
                   {:success false
                    :error {:message (ex-message e)
                            :data (ex-data e)}
                    :metrics {:tokens 0 :duration-ms 0}}))]

    (-> ctx
        (assoc-in [:phase :name] :release)
        (assoc-in [:phase :agent] :releaser)
        (assoc-in [:phase :gates] gates)
        (assoc-in [:phase :budget] budget)
        (assoc-in [:phase :started-at] start-time)
        (assoc-in [:phase :status] :running)
        (assoc-in [:phase :result] result))))

(defn- leave-release
  "Post-processing for release phase.

   Records release metrics."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        metrics (get result :metrics {:tokens 0 :duration-ms duration-ms})
        iterations (get-in ctx [:phase :iterations] 1)]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] metrics)
        (assoc-in [:metrics :release :duration-ms] duration-ms)
        (assoc-in [:metrics :release :repair-cycles] (dec iterations))
        (update-in [:execution :phases-completed] (fnil conj []) :release)
        ;; Merge agent metrics into execution metrics
        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))))

(defn- error-release
  "Handle release phase errors."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 2)
        on-fail (get-in ctx [:phase-config :on-fail])]
    (cond
      ;; Within budget - retry
      (< iterations max-iterations)
      (-> ctx
          (update-in [:phase :iterations] (fnil inc 0))
          (assoc-in [:phase :last-error] (ex-message ex))
          (assoc-in [:phase :status] :retrying))

      ;; Has on-fail transition - redirect
      on-fail
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :redirect-to] on-fail)
          (assoc-in [:phase :error] {:message (ex-message ex)
                                     :data (ex-data ex)}))

      ;; No recovery - propagate
      :else
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :error] {:message (ex-message ex)
                                     :data (ex-data ex)})))))

;------------------------------------------------------------------------------ Layer 2
;; Registry methods

(defmethod registry/get-phase-interceptor :release
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::release
     :config merged
     :enter (fn [ctx]
              (enter-release (assoc ctx :phase-config merged)))
     :leave leave-release
     :error error-release}))

(defmethod registry/get-phase-interceptor :done
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::done
     :config merged
     :enter (fn [ctx]
              (-> ctx
                  (assoc-in [:phase :name] :done)
                  (assoc-in [:phase :status] :completed)
                  (assoc-in [:execution :status] :completed)))
     :leave identity
     :error (fn [ctx _ex] ctx)}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (registry/get-phase-interceptor {:phase :release})
  (registry/get-phase-interceptor {:phase :done})
  (registry/phase-defaults :release)
  (registry/phase-defaults :done)
  (registry/list-phases)
  :leave-this-here)
