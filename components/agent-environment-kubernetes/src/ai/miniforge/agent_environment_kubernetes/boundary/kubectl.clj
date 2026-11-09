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
(ns ai.miniforge.agent-environment-kubernetes.boundary.kubectl
  "The one place kubectl runs: always with an explicit `--context`, stdin
   when given, and a deadline. A process that cannot start or outlives its
   deadline becomes an anomaly here; callers only see data."
  (:require
   [ai.miniforge.agent-environment-kubernetes.messages :as msg]
   [ai.miniforge.anomaly.interface :as anomaly]
   [babashka.process :as p]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} timed-out
  [args]
  (anomaly/sub-anomaly :timeout :anomalies.agent-environment-kubernetes/kubectl-timeout
                       (msg/t :anomalies.agent-environment-kubernetes/kubectl-timeout) {:kubectl/args args}))

(defn- ^{:stratum 0} start-failed
  [args e]
  (anomaly/exception-anomaly :unavailable (msg/t :anomalies.agent-environment-kubernetes/kubectl-unavailable)
                             {:kubectl/args args} e))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run
  "Run kubectl against the configured context: `{:kubectl/exit :kubectl/out
   :kubectl/err}`, or a timeout or unavailable anomaly."
  [settings args stdin]
  (let [kubeconfig (:kubernetes/kubeconfig settings)
        argv (cond-> [(:kubernetes/kubectl settings)]
               kubeconfig (into ["--kubeconfig" kubeconfig])
               true (into ["--context" (:kubernetes/context settings)])
               true (into args))]
    (try+
      (let [proc (apply p/process {:in (or stdin "") :out :string :err :string} argv)
            done (deref proc (:kubernetes/command-timeout-ms settings) ::timeout)]
        (if (= ::timeout done)
          (do (p/destroy-tree proc) (timed-out args))
          {:kubectl/exit (:exit done) :kubectl/out (:out done) :kubectl/err (:err done)}))
      (catch Object _
        (start-failed args (:throwable &throw-context))))))
