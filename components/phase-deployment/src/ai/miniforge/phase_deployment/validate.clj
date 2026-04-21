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

(ns ai.miniforge.phase-deployment.validate
  "Validate phase interceptor."
  (:require [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase-deployment.defaults :as defaults]
            [ai.miniforge.phase-deployment.evidence :as evidence]
            [ai.miniforge.phase-deployment.messages :as msg]
            [ai.miniforge.phase-deployment.shell :as shell]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.schema.interface :as schema]
            [clojure.string :as str])
  (:import [java.net HttpURLConnection URL]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults + schemas

(def default-config
  "Validate phase defaults loaded from EDN."
  (defaults/phase-defaults :validate))

(def ValidateRunConfig
  [:map
   [:phase-config map?]
   [:health-endpoints [:vector :string]]
   [:smoke-commands [:vector :string]]
   [:retries pos-int?]
   [:retry-delay-ms nat-int?]])

(def HealthCheckResult
  [:map
   [:passed? :boolean]
   [:url :string]
   [:status-code {:optional true} [:maybe int?]]
   [:duration-ms nat-int?]
   [:attempts {:optional true} pos-int?]
   [:error {:optional true} :string]])

(def SmokeTestResult
  [:map
   [:command :string]
   [:passed? :boolean]
   [:stdout {:optional true} [:maybe :string]]
   [:stderr {:optional true} [:maybe :string]]
   [:duration-ms nat-int?]])

(def ValidationBatch
  [:map
   [:passed? :boolean]
   [:results [:vector any?]]])

(defn- validate!
  [result-schema value]
  (schema/validate result-schema value))

;------------------------------------------------------------------------------ Layer 1
;; Shared helpers

(defn- get-logger
  "Resolve logger from ctx, creating a default if absent."
  [ctx]
  (or (get-in ctx [:execution/logger])
      (log/create-logger {:min-level :info :output :human})))

(defn- merged-phase-config
  [ctx phase-kw]
  (phase/merge-with-defaults
   (assoc (or (get-in ctx [:phase-config]) {}) :phase phase-kw)))

(defn- resolve-validate-config
  [ctx]
  (let [phase-config (merged-phase-config ctx :validate)
        input        (or (get-in ctx [:execution/input]) {})]
    (validate!
     ValidateRunConfig
     {:phase-config     phase-config
      :health-endpoints (get input :health-endpoints
                             (get phase-config :health-endpoints []))
      :smoke-commands   (get input :smoke-commands
                             (get phase-config :smoke-commands []))
      :retries          (get input :retries
                             (get phase-config :retries 3))
      :retry-delay-ms   (get input :retry-delay-ms
                             (get phase-config :retry-delay-ms 5000))})))

(defn- passed?
  [result]
  (true? (:passed? result)))

(defn- batch-result
  [result-schema results]
  (validate!
   ValidationBatch
   {:passed? (every? passed? results)
    :results (mapv #(validate! result-schema %) results)}))

(defn- passed-check
  [url status-code duration-ms]
  (validate!
   HealthCheckResult
   {:passed? true
    :url url
    :status-code status-code
    :duration-ms duration-ms}))

(defn- failed-check
  [url status-code duration-ms error]
  (validate!
   HealthCheckResult
   {:passed? false
    :url url
    :status-code status-code
    :duration-ms duration-ms
    :error error}))

(defn- smoke-test-result
  [cmd result]
  (validate!
   SmokeTestResult
   {:command     cmd
    :passed?     (schema/succeeded? result)
    :stdout      (:stdout result)
    :stderr      (:stderr result)
    :duration-ms (:duration-ms result)}))

;; Health check execution + smoke tests

(defn- http-health-check
  "Execute an HTTP health check against an endpoint."
  [url & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (let [start (System/currentTimeMillis)]
    (try
      (let [conn (doto ^HttpURLConnection (.openConnection (URL. url))
                   (.setRequestMethod "GET")
                   (.setConnectTimeout timeout-ms)
                   (.setReadTimeout timeout-ms)
                   (.setInstanceFollowRedirects true))
            status (.getResponseCode conn)
            duration (- (System/currentTimeMillis) start)]
        (.disconnect conn)
        (if (<= 200 status 299)
          (passed-check url status duration)
          (failed-check url status duration nil)))
      (catch Exception e
        (failed-check url
                      nil
                      (- (System/currentTimeMillis) start)
                      (ex-message e))))))

(defn- check-health-endpoints
  "Check multiple health endpoints with retry."
  [endpoints & {:keys [retries retry-delay] :or {retries 3 retry-delay 5000}}]
  (let [results (mapv (fn [url]
                        (loop [attempt 1]
                          (let [result (http-health-check url)]
                            (if (or (get result :passed? false)
                                    (>= attempt retries))
                              (assoc result :attempts attempt)
                              (do
                                (Thread/sleep retry-delay)
                                (recur (inc attempt)))))))
                      endpoints)]
    (batch-result HealthCheckResult results)))

(defn- run-smoke-tests
  "Execute smoke test commands."
  [commands]
  (let [results (mapv (fn [cmd]
                        (let [[bin & args] (str/split cmd #"\s+")
                              result (shell/sh-with-timeout bin (vec args) :timeout-ms 30000)]
                          (smoke-test-result cmd result)))
                      commands)]
    (batch-result SmokeTestResult results)))

(defn- validation-metrics
  [duration health-results smoke-results]
  {:duration-ms   duration
   :checks-run    (+ (count (:results health-results))
                     (count (:results smoke-results)))
   :checks-passed (+ (count (filter :passed? (:results health-results)))
                     (count (filter :passed? (:results smoke-results))))})

(defn- log-validation-outcome
  [logger duration health-results smoke-results]
  (if (and (:passed? health-results) (:passed? smoke-results))
    (log/info logger :validate :validate/passed
              {:data {:duration-ms duration}})
    (log/error logger :validate :validate/failed
               {:data {:health-failures (count (remove :passed? (:results health-results)))
                       :smoke-failures  (count (remove :passed? (:results smoke-results)))}})))

(defn- invalid-config-result
  [ctx start-time ex]
  (-> ctx
      (assoc-in [:phase :name] :validate)
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :started-at] start-time)
      (assoc-in [:phase :result]
                {:status :error
                 :error  (msg/t :validate/invalid-config
                                {:error (ex-message ex)})})))

;------------------------------------------------------------------------------ Layer 2
;; Phase interceptors + registration

(defn enter-validate
  "Execute post-deployment validation."
  [ctx]
  (let [start-time (System/currentTimeMillis)
        logger     (get-logger ctx)]
    (try
      (let [config            (resolve-validate-config ctx)
            endpoints         (:health-endpoints config)
            smoke-commands    (:smoke-commands config)
            _                 (log/info logger :validate :validate/starting
                                        {:data {:endpoint-count (count endpoints)
                                                :smoke-count    (count smoke-commands)}})
            health-results    (if (seq endpoints)
                                (check-health-endpoints endpoints
                                                        :retries (:retries config)
                                                        :retry-delay (:retry-delay-ms config))
                                (batch-result HealthCheckResult []))
            smoke-results     (if (seq smoke-commands)
                                (run-smoke-tests smoke-commands)
                                (batch-result SmokeTestResult []))
            validation-passed? (and (:passed? health-results)
                                    (:passed? smoke-results))
            duration          (- (System/currentTimeMillis) start-time)
            metrics           (validation-metrics duration health-results smoke-results)
            validation-evidence (evidence/create-evidence
                                 :evidence/validation-results
                                 {:health-checks (:results health-results)
                                  :smoke-tests   (:results smoke-results)
                                  :passed?       validation-passed?})]
        (log-validation-outcome logger duration health-results smoke-results)
        (-> ctx
            (assoc-in [:phase :name] :validate)
            (assoc-in [:phase :gates] (get-in config [:phase-config :gates]))
            (assoc-in [:phase :budget] (get-in config [:phase-config :budget]))
            (assoc-in [:phase :started-at] start-time)
            (assoc-in [:phase :status] (if validation-passed? :completed :failed))
            (assoc-in [:phase :result]
                      {:status   (if validation-passed? :success :validation-failed)
                       :output   {:health health-results
                                  :smoke  smoke-results}
                       :artifact {:content  {:health-results (:results health-results)
                                             :smoke-results  (:results smoke-results)}
                                  :type     :validation-state
                                  :passed? validation-passed?}
                       :metrics  metrics})
            (evidence/add-evidence-to-ctx validation-evidence)))
      (catch clojure.lang.ExceptionInfo ex
        (invalid-config-result ctx start-time ex)))))

(defn leave-validate
  "Post-validation: pass through."
  [ctx]
  ctx)

(defn error-validate
  "Handle validate phase errors."
  [ctx ex]
  (let [logger (get-logger ctx)]
    (log/error logger :validate :validate/error
               {:data {:message (ex-message ex)
                       :data    (ex-data ex)}})
    (-> ctx
        (assoc-in [:phase :status] :failed)
        (update :execution/errors (fnil conj [])
                {:type    :validate-error
                 :phase   :validate
                 :message (ex-message ex)
                 :data    (ex-data ex)}))))

;; Registration

(defmethod phase/get-phase-interceptor-method :validate
  [_]
  {:name   :validate
   :enter  enter-validate
   :leave  leave-validate
   :error  error-validate
   :config default-config})

;------------------------------------------------------------------------------ Rich Comment
(comment
  (enter-validate {:execution/input {:health-endpoints ["http://localhost:5555/healthz"]}})
  :leave-this-here)
