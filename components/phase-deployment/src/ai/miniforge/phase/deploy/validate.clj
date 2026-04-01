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

(ns ai.miniforge.phase.deploy.validate
  "Validate phase interceptor.

   Post-deployment validation: health checks, smoke tests, connectivity.
   Runs after deploy phase completes to verify the deployment is functional.

   Agent: none (HTTP checks and CLI commands, no LLM)
   Default gates: [:health-check]"
  (:require [clojure.string :as str]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.deploy.evidence :as evidence]
            [ai.miniforge.phase.deploy.shell :as shell]
            [ai.miniforge.phase.registry :as registry])
  (:import [java.net HttpURLConnection URL]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  "Phase defaults — overridable via workflow EDN."
  {:gates  [:health-check]
   :budget {:tokens 0 :iterations 3 :time-seconds 120}
   :agent  nil})

(registry/register-phase-defaults! :validate default-config)

;------------------------------------------------------------------------------ Layer 1
;; Health check execution + smoke tests

(defn- http-health-check
  "Execute an HTTP health check against an endpoint.

   Arguments:
     url        - Full URL to check (e.g. \"http://svc.ns.svc.cluster.local/health\")
     timeout-ms - Connection and read timeout (default 10000)

   Returns:
     {:passed?     boolean
      :url         string
      :status-code int (or nil on connection failure)
      :duration-ms long
      :error       string (if failed)}"
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
        {:passed?     (<= 200 status 299)
         :url         url
         :status-code status
         :duration-ms duration})
      (catch Exception e
        {:passed?     false
         :url         url
         :status-code nil
         :duration-ms (- (System/currentTimeMillis) start)
         :error       (ex-message e)}))))

(defn- check-health-endpoints
  "Check multiple health endpoints with retry.

   Arguments:
     endpoints   - Vector of URL strings
     retries     - Number of retries per endpoint (default 3)
     retry-delay - Milliseconds between retries (default 5000)

   Returns:
     {:all-passed? boolean
      :results     [{:url :passed? :status-code :duration-ms :attempts}]}"
  [endpoints & {:keys [retries retry-delay] :or {retries 3 retry-delay 5000}}]
  (let [results
        (mapv (fn [url]
                (loop [attempt 1]
                  (let [result (http-health-check url)]
                    (if (or (:passed? result) (>= attempt retries))
                      (assoc result :attempts attempt)
                      (do (Thread/sleep retry-delay)
                          (recur (inc attempt)))))))
              endpoints)]
    {:all-passed? (every? :passed? results)
     :results     results}))

;; Smoke test execution

(defn- run-smoke-tests
  "Execute smoke test commands.

   Arguments:
     commands - Vector of shell command strings
     opts     - :namespace, :context for kubectl-based tests

   Returns:
     {:all-passed? boolean
      :results     [{:command :passed? :stdout :stderr :duration-ms}]}"
  [commands & {:keys [_namespace _context]}]
  (let [results
        (mapv (fn [cmd]
                (let [[bin & args] (str/split cmd #"\s+")
                      result (shell/sh-with-timeout bin (vec args) :timeout-ms 30000)]
                  {:command     cmd
                   :passed?     (:success? result)
                   :stdout      (:stdout result)
                   :stderr      (:stderr result)
                   :duration-ms (:duration-ms result)}))
              commands)]
    {:all-passed? (every? :passed? results)
     :results     results}))

;------------------------------------------------------------------------------ Layer 2
;; Phase interceptors + registration

(defn enter-validate
  "Execute post-deployment validation.

   Reads validation config from execution input:
     :health-endpoints - Vector of HTTP URLs to health-check
     :smoke-commands   - Optional vector of shell commands to run
     :retries          - Number of health check retries (default 3)
     :retry-delay-ms   - Delay between retries (default 5000)"
  [ctx]
  (let [config       (registry/merge-with-defaults (get-in ctx [:phase-config]) :validate)
        start-time   (System/currentTimeMillis)
        logger       (or (get-in ctx [:execution/logger])
                         (log/create-logger {:min-level :info :output :human}))
        input        (get-in ctx [:execution/input])
        endpoints    (or (:health-endpoints input) (:health-endpoints config) [])
        smoke-cmds   (or (:smoke-commands input) (:smoke-commands config) [])
        retries      (get input :retries 3)
        retry-delay  (get input :retry-delay-ms 5000)]

    (log/info logger :validate :validate/starting
              {:data {:endpoint-count (count endpoints)
                      :smoke-count    (count smoke-cmds)}})

    ;; Step 1: Health checks
    (let [health-results (if (seq endpoints)
                           (check-health-endpoints endpoints
                                                   :retries retries
                                                   :retry-delay retry-delay)
                           {:all-passed? true :results []})

          ;; Step 2: Smoke tests
          smoke-results (if (seq smoke-cmds)
                          (run-smoke-tests smoke-cmds)
                          {:all-passed? true :results []})

          all-passed? (and (:all-passed? health-results)
                          (:all-passed? smoke-results))
          duration    (- (System/currentTimeMillis) start-time)

          validation-evidence (evidence/create-evidence
                               :evidence/validation-results
                               {:health-checks (:results health-results)
                                :smoke-tests   (:results smoke-results)
                                :all-passed?   all-passed?})]

      (if all-passed?
        (log/info logger :validate :validate/passed
                  {:data {:duration-ms duration}})
        (log/error logger :validate :validate/failed
                   {:data {:health-failures (count (remove :passed? (:results health-results)))
                           :smoke-failures  (count (remove :passed? (:results smoke-results)))}}))

      (-> ctx
          (assoc-in [:phase :name] :validate)
          (assoc-in [:phase :gates] (:gates config))
          (assoc-in [:phase :budget] (:budget config))
          (assoc-in [:phase :started-at] start-time)
          (assoc-in [:phase :status] (if all-passed? :completed :failed))
          (assoc-in [:phase :result]
                    {:status   (if all-passed? :success :validation-failed)
                     :output   {:health health-results
                                :smoke  smoke-results}
                     :artifact {:content    {:health-results (:results health-results)
                                             :smoke-results  (:results smoke-results)}
                                :type       :validation-state
                                :all-passed? all-passed?}
                     :metrics  {:duration-ms  duration
                                :checks-run   (+ (count (:results health-results))
                                                 (count (:results smoke-results)))
                                :checks-passed (+ (count (filter :passed? (:results health-results)))
                                                  (count (filter :passed? (:results smoke-results))))}})
          (evidence/add-evidence-to-ctx validation-evidence)))))

(defn leave-validate
  "Post-validation: pass through."
  [ctx]
  ctx)

(defn error-validate
  "Handle validate phase errors."
  [ctx ex]
  (let [logger (or (get-in ctx [:execution/logger])
                   (log/create-logger {:min-level :error :output :human}))]
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

(defmethod registry/get-phase-interceptor :validate
  [_]
  {:name   :validate
   :enter  enter-validate
   :leave  leave-validate
   :error  error-validate
   :config default-config})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test validation against a running service
  ;; (enter-validate {:execution/input {:health-endpoints ["http://localhost:5555/healthz"]}})

  :leave-this-here)
