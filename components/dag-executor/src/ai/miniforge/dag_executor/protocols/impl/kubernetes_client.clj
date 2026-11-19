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
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-client
  "Run legacy Kubernetes transport and observe Pod readiness."
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.result :as result]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]])
  (:import [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} interrupted []
  (let [error (result/err :pod-interrupted (messages/t :kubernetes/pod-interrupted))]
    (.interrupt (Thread/currentThread))
    error))

(defn ^{:stratum 0} run-kubectl
  "Execute a kubectl command and return the result."
  [kubectl-path & args]
  (try+
    (apply shell/sh (or kubectl-path "kubectl") args)
    (catch Exception e
      (when (instance? InterruptedException e)
        (.interrupt (Thread/currentThread)))
      {:exit 1 :err (.getMessage e) :out ""})))

(defn- ^{:stratum 0} probe-ready [executable namespace job-name until]
  (let [listing (command/run! until [executable "get" "pods" "-n" namespace
                                    "-l" (str "job-name=" job-name) "-o" "jsonpath={.items[0].metadata.name}"])
        pod-name (when (= 0 (:exit listing)) (some-> (:out listing) str/trim))
        status (when-not (str/blank? pod-name)
                 (command/run! until [executable "get" "pod" pod-name "-n" namespace
                                      "-o" "jsonpath={.status.phase}"]))
        phase (when (= 0 (:exit status)) (some-> (:out status) str/trim))]
    (cond
      (deadline/expired? until) nil
      (= "Running" phase) (result/ok {:pod-name pod-name :status :running})
      (contains? #{"Failed" "Succeeded"} phase)
      (result/err :pod-failed (messages/t :kubernetes/pod-ended {:phase phase})))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} get-pod-for-job [kubectl-path namespace job-name]
  (let [{:keys [exit out] :as reply}
        (run-kubectl kubectl-path "get" "pods" "-n" namespace
                     "-l" (str "job-name=" job-name) "-o" "jsonpath={.items[0].metadata.name}")]
    (if (and (zero? exit) (not (str/blank? out)))
      (result/ok {:pod-name (str/trim out)})
      (result/err :pod-not-found (get reply :err (messages/t :kubernetes/pod-not-found))))))

(defn ^{:stratum 1} wait-for-pod-ready
  [kubectl-path namespace job-name {:kubernetes/keys [pod-ready-timeout-ms pod-ready-poll-interval-ms]}]
  (let [until (deadline/after-ms pod-ready-timeout-ms)
        interval-ns (* pod-ready-poll-interval-ms deadline/nanos-per-ms)
        executable (if (nil? kubectl-path) "kubectl" kubectl-path)]
    (try+
      (loop []
        (cond
          (.isInterrupted (Thread/currentThread)) (interrupted)
          (deadline/expired? until) (result/err :pod-timeout (messages/t :kubernetes/pod-timeout))
          :else
          (if-let [observed (probe-ready executable namespace job-name until)]
            observed
            (do
              (.sleep TimeUnit/NANOSECONDS (min interval-ns (deadline/remaining-ns until)))
              (recur)))))
      (catch InterruptedException _ (interrupted)))))
