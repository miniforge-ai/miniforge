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
(ns ai.miniforge.cli.workflow-runner.preflight-probe
  "Backend CLI probes: version read and the health-check round trip
   (Claude's direct `-p` probe vs the generic streamed-CLI probe).
   Split out of `ai.miniforge.cli.workflow-runner` (rule 210: the
   parent namespace measured 10 real layers, max 3; each concern moves
   to its own layer-coherent file)."
  (:require
   [clojure.string :as str]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.preflight-support :as support]
   [ai.miniforge.cli.workflow-runner.process :as process]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} read-cli-version
  [cmd-path]
  (let [{:keys [out err exit timeout-ms]} (process/run-cli-command [cmd-path "--version"] (support/backend-version-timeout-ms))]
    (cond
      timeout-ms
      (support/failure-response :anomalies/unavailable
                                "backend_version_timeout"
                                (messages/t :workflow-runner/backend-version-timeout
                                            {:timeout-ms timeout-ms})
                                {:cmd-path cmd-path
                                 :timeout-ms timeout-ms})

      (zero? exit)
      (if-let [version (or (some-> out str/trim not-empty)
                           (some-> err str/trim not-empty))]
        (support/success-response {:version version})
        (support/failure-response :anomalies/unavailable
                                  "backend_version_empty_output"
                                  (messages/t :workflow-runner/backend-version-empty)
                                  {:cmd-path cmd-path
                                   :exit-code exit}))

      :else
      (support/failure-response :anomalies/unavailable
                                "backend_version_cli_error"
                                (or (some-> err str/trim not-empty)
                                    (some-> out str/trim not-empty)
                                    (messages/t :workflow-runner/backend-version-exit
                                                {:exit-code exit}))
                                {:cmd-path cmd-path
                                 :exit-code exit}))))

(defn- ^{:stratum 0} claude-preflight-command
  [cmd-path]
  (into [cmd-path "-p" (support/backend-preflight-prompt)]
        (support/claude-preflight-args)))

(defn- ^{:stratum 0} generic-preflight-invocation
  "Build `{:args :stdin}` for a non-Claude backend's health probe the
   same way the llm brick builds them for a real call: the backend's
   declared `:prompt-via` goes into the request so `:args-fn` shapes
   argv for that mode, and a `:stdin` backend (codex, whose argv ends
   in the `-` placeholder) gets the prompt piped in rather than left
   out entirely. The caller prepends the resolved CLI path."
  [llm-client]
  (let [{:keys [backend model]} (:config llm-client)
        {:keys [args-fn] :as backend-config} (get llm/backends backend)
        prompt (support/backend-preflight-prompt)
        prompt-via (llm/backend-prompt-via backend-config)
        request (cond-> {:prompt prompt :prompt-via prompt-via}
                  model (assoc :model model))]
    {:args (vec (args-fn request))
     :stdin (when (= :stdin prompt-via) prompt)}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-generic-backend-preflight
  [llm-client cmd-path workdir]
  (let [{:keys [backend]} (:config llm-client)
        backend-config (get llm/backends backend)
        {:keys [args stdin]} (generic-preflight-invocation llm-client)
        full-cmd (into [cmd-path] args)
        {:keys [out err exit timeout-ms]} (process/run-cli-command full-cmd
                                                                   (support/backend-preflight-timeout-ms)
                                                                   :workdir workdir
                                                                   :stdin stdin)
        content (support/decoded-preflight-content backend-config out)]
    (cond
      timeout-ms
      (support/failure-response :anomalies/unavailable
                                "backend_preflight_timeout"
                                err
                                {:cmd-path cmd-path
                                 :timeout-ms timeout-ms
                                 :exit-code exit})

      (not (zero? exit))
      (support/failure-response :anomalies/unavailable
                                "backend_preflight_cli_error"
                                (or (some-> err str/trim not-empty)
                                    content
                                    (messages/t :workflow-runner/backend-preflight-exit
                                                {:exit-code exit}))
                                {:cmd-path cmd-path
                                 :stdout (some-> out str/trim not-empty)
                                 :stderr (some-> err str/trim not-empty)
                                 :exit-code exit})

      (support/preflight-success? content)
      (support/success-response {:content content
                                 :exit-code exit})

      :else
      (support/failure-response :anomalies/unavailable
                                "backend_preflight_unexpected_output"
                                (messages/t :workflow-runner/backend-preflight-failed
                                            {:backend (name backend)})
                                {:cmd-path cmd-path
                                 :stdout (some-> out str/trim not-empty)
                                 :stderr (some-> err str/trim not-empty)
                                 :content (support/normalized-preflight-content content)
                                 :exit-code exit}))))

(defn- ^{:stratum 1} run-claude-backend-preflight
  [cmd-path workdir]
  (let [{:keys [out err exit timeout-ms]} (process/run-cli-command (claude-preflight-command cmd-path)
                                                                   (support/backend-preflight-timeout-ms)
                                                                   :workdir workdir)
        trimmed (some-> out str/trim)
        content (support/normalized-preflight-content trimmed)]
    (cond
      timeout-ms
      (assoc (support/failure-response :anomalies/unavailable
                                       "backend_preflight_timeout"
                                       err
                                       {:cmd-path cmd-path
                                        :timeout-ms timeout-ms
                                        :exit-code exit})
             :exit-code exit)

      (not (zero? exit))
      (assoc (support/failure-response :anomalies/unavailable
                                       "backend_preflight_cli_error"
                                       (or (some-> err str/trim not-empty)
                                           trimmed
                                           (messages/t :workflow-runner/backend-preflight-exit
                                                       {:exit-code exit}))
                                       {:cmd-path cmd-path
                                        :exit-code exit})
             :exit-code exit)

      (support/preflight-success? content)
      (-> (support/success-response {:content content
                                     :exit-code exit})
          (assoc :exit-code exit
                 :content content))

      :else
      (assoc (support/failure-response :anomalies/unavailable
                                       "backend_preflight_unexpected_output"
                                       (messages/t :workflow-runner/claude-preflight-unexpected-output)
                                       {:cmd-path cmd-path
                                        :stdout trimmed
                                        :content content
                                        :stderr (some-> err str/trim not-empty)
                                        :exit-code exit})
             :exit-code exit))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-backend-probe
  [llm-client {:keys [backend cmd-path]} workdir]
  (if (= backend :claude)
    (run-claude-backend-preflight cmd-path workdir)
    (run-generic-backend-preflight llm-client cmd-path workdir)))
