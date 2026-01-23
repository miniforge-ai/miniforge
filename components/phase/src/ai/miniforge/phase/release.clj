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
   Agent: :releaser
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

(defn- enter-release
  "Execute release phase.

   Prepares release artifacts, creates PR if configured."
  [ctx]
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [agent gates budget]} config
        start-time (System/currentTimeMillis)]
    (-> ctx
        (assoc-in [:phase :name] :release)
        (assoc-in [:phase :agent] agent)
        (assoc-in [:phase :gates] gates)
        (assoc-in [:phase :budget] budget)
        (assoc-in [:phase :started-at] start-time)
        (assoc-in [:phase :status] :running))))

(defn- leave-release
  "Post-processing for release phase.

   Records release metrics."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (update-in [:execution :phases-completed] (fnil conj []) :release))))

(defn- error-release
  "Handle release phase errors."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 2)]
    (if (< iterations max-iterations)
      (-> ctx
          (update-in [:phase :iterations] (fnil inc 0))
          (assoc-in [:phase :last-error] (ex-message ex))
          (assoc-in [:phase :status] :retrying))
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
