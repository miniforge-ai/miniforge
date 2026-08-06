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
(ns ai.miniforge.cli.workflow-runner.preflight
  "Backend preflight orchestration: resolve the backend's CLI stamp
   (path + version), print its provenance, and verify the health probe
   before a workflow runs. Fails closed with anomalies carrying the
   resolved stamp so failures are attributable. Split out of
   `ai.miniforge.cli.workflow-runner` (rule 210: the parent namespace
   measured 10 real layers, max 3; each concern moves to its own
   layer-coherent file)."
  (:require
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.paths :as paths]
   [ai.miniforge.cli.workflow-runner.preflight-probe :as probe]
   [ai.miniforge.cli.workflow-runner.preflight-support :as support]
   [ai.miniforge.cli.workflow-runner.provenance :as provenance]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} backend-cli-missing!
  [{:keys [backend cmd]}]
  (response/throw-anomaly! :anomalies/incorrect
                           (messages/t :workflow-runner/cli-not-on-path {:cmd cmd})
                           {:backend backend
                            :cmd cmd
                            :path (System/getenv "PATH")}))

(defn- ^{:stratum 0} backend-version-failed!
  [{:keys [backend cmd cmd-path]} version-response]
  (response/throw-anomaly! :anomalies/unavailable
                           (messages/t :workflow-runner/backend-version-failed
                                       {:backend (name backend)})
                           {:backend backend
                            :cmd cmd
                            :cmd-path cmd-path
                            :version-error (get-in version-response [:error :message])
                            :version-error-data (get-in version-response [:error :data])}))

(defn- ^{:stratum 0} backend-preflight-failed!
  [{:keys [backend cmd cmd-path cmd-version]} probe-response]
  (response/throw-anomaly! :anomalies/unavailable
                           (messages/t :workflow-runner/backend-preflight-failed
                                       {:backend (name backend)})
                           {:backend backend
                            :cmd cmd
                            :cmd-path cmd-path
                            :cmd-version cmd-version
                            :probe-response (support/response-summary probe-response)}))

(defn- ^{:stratum 0} backend-stamp
  [llm-client]
  (when-let [backend (llm/client-backend llm-client)]
    (let [backend-config (get llm/backends backend)
          cmd (:cmd backend-config)
          cmd-path (when (:requires-cli? backend-config)
                     (paths/resolve-cli-command-path cmd))]
      {:backend backend
       :backend-config backend-config
       :cmd cmd
       :cmd-path cmd-path})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} ensure-cli-command-path!
  [stamp]
  (when-not (:cmd-path stamp)
    (backend-cli-missing! stamp))
  stamp)

(defn- ^{:stratum 1} versioned-backend-stamp
  [stamp]
  (let [version-response (probe/read-cli-version (:cmd-path stamp))
        version (get-in (support/response-output version-response) [:version])]
    (when-not (support/response-succeeded? version-response)
      (backend-version-failed! stamp version-response))
    (support/with-backend-version stamp version)))

(defn- ^{:stratum 1} cli-required-backend-stamp
  [llm-client]
  (when-let [stamp (backend-stamp llm-client)]
    (when (:requires-cli? (:backend-config stamp))
      stamp)))

(defn- ^{:stratum 1} verify-backend-probe!
  [llm-client stamp workdir]
  (let [probe-response (probe/run-backend-probe llm-client stamp workdir)]
    (when-not (support/response-succeeded? probe-response)
      (backend-preflight-failed! stamp probe-response))
    stamp))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-backend-preflight!
  [quiet llm-client context]
  (when-let [stamp (cli-required-backend-stamp llm-client)]
    (let [stamp (-> stamp
                    ensure-cli-command-path!
                    versioned-backend-stamp)]
      (provenance/print-backend-provenance! quiet stamp)
      (verify-backend-probe! llm-client stamp (:worktree-path context)))))
