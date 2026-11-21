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
            [ai.miniforge.dag-executor.result :as result]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} run-kubectl
  "Execute a kubectl command and return the result."
  [kubectl-path & args]
  (try+
    (apply shell/sh (or kubectl-path "kubectl") args)
    (catch Exception e
      (when (instance? InterruptedException e)
        (.interrupt (Thread/currentThread)))
      {:exit 1 :err (.getMessage e) :out ""})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} get-pod-for-job [kubectl-path namespace job-name]
  (let [{:keys [exit out] :as reply}
        (run-kubectl kubectl-path "get" "pods" "-n" namespace
                     "-l" (str "job-name=" job-name) "-o" "jsonpath={.items[0].metadata.name}")]
    (if (and (zero? exit) (not (str/blank? out)))
      (result/ok {:pod-name (str/trim out)})
      (result/err :pod-not-found (get reply :err (messages/t :kubernetes/pod-not-found))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} wait-for-pod-ready
  [kubectl-path namespace job-name {:kubernetes/keys [pod-ready-timeout-ms pod-ready-poll-interval-ms]}]
  (let [deadline (+ (System/currentTimeMillis) pod-ready-timeout-ms)]
    (loop []
      (if (> (System/currentTimeMillis) deadline)
        (result/err :pod-timeout (messages/t :kubernetes/pod-timeout))
        (let [pod-name (get-in (get-pod-for-job kubectl-path namespace job-name) [:data :pod-name])
              out (when pod-name (:out (run-kubectl kubectl-path "get" "pod" pod-name "-n" namespace
                                                     "-o" "jsonpath={.status.phase}")))
              phase (some-> out str/trim)]
          (cond
            (= "Running" phase) (result/ok {:pod-name pod-name :status :running})
            (contains? #{"Failed" "Succeeded"} phase) (result/err :pod-failed (messages/t :kubernetes/pod-ended {:phase phase}))
            :else (do (Thread/sleep pod-ready-poll-interval-ms) (recur))))))))
