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
(ns ai.miniforge.llm.protocols.impl.llm-client
  "Implementation functions for LLMClient protocol.

   This namespace holds the streaming entry point (`complete-stream-impl`
   and the handlers it dispatches to), `get-config-impl`, and the default
   capture exec-fn. The rest lives in the `llm-client.*` namespaces:
   `responses` (the response contract and anomaly categories), `settings`
   (the EDN resources and environment reads), `prompts` (prompt assembly,
   delivery and size), `cli-args` and `cli-output` (agent CLI arguments
   and output), `backend-registry` (the backends and their probe
   endpoints), `http-wire` and `http-backend` (the direct HTTP providers),
   `subprocess`, `stream-reader` and `stream-exec` (running the agent
   CLI), and `completion` (`complete-impl` and the exec-fn adapters). The
   aliases in Layer 0 keep the moved definitions' names here for callers."
  (:require
   [clojure.string :as str]
   [ai.miniforge.llm.cost :as cost]
   [ai.miniforge.llm.messages :as msg]
   [ai.miniforge.llm.model-registry :as model-registry]
   [ai.miniforge.llm.progress-monitor :as pm]
   [ai.miniforge.llm.protocols.impl.llm-client.backend-registry :as backend-registry]
   [ai.miniforge.llm.protocols.impl.llm-client.cli-args :as cli-args]
   [ai.miniforge.llm.protocols.impl.llm-client.cli-output :as cli-output]
   [ai.miniforge.llm.protocols.impl.llm-client.completion :as completion]
   [ai.miniforge.llm.protocols.impl.llm-client.http-backend :as http-backend]
   [ai.miniforge.llm.protocols.impl.llm-client.http-wire :as http-wire]
   [ai.miniforge.llm.protocols.impl.llm-client.prompts :as prompts]
   [ai.miniforge.llm.protocols.impl.llm-client.responses :as responses]
   [ai.miniforge.llm.protocols.impl.llm-client.settings :as settings]
   [ai.miniforge.llm.protocols.impl.llm-client.stream-exec :as stream-exec]
   [ai.miniforge.llm.protocols.impl.llm-client.stream-reader :as stream-reader]
   [ai.miniforge.llm.protocols.impl.llm-client.subprocess :as subprocess]
   [ai.miniforge.logging.interface :as log]
   [slingshot.slingshot :refer [throw+ try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} get-config-impl [client]
  (:config client))

;; Aliases: the moved definitions keep their llm-client names for callers.
(def ^{:stratum 0} anthropic-request-body
  "Alias of `http-wire/anthropic-request-body`."
  http-wire/anthropic-request-body)

(def ^{:stratum 0} backends
  "Alias of `backend-registry/backends`."
  backend-registry/backends)

(def ^{:stratum 0} build-messages-prompt
  "Alias of `prompts/build-messages-prompt`."
  prompts/build-messages-prompt)

(def ^{:stratum 0} capsule-exec-fn
  "Alias of `completion/capsule-exec-fn`."
  completion/capsule-exec-fn)

(def ^{:stratum 0} claude-args
  "Alias of `cli-args/claude-args`."
  cli-args/claude-args)

(def ^{:stratum 0} claude-mcp-allowlist-string
  "Alias of `cli-args/claude-mcp-allowlist-string`."
  cli-args/claude-mcp-allowlist-string)

(def ^{:stratum 0} codex-args
  "Alias of `cli-args/codex-args`."
  cli-args/codex-args)

(def ^{:stratum 0} complete-impl
  "Alias of `completion/complete-impl`."
  completion/complete-impl)

(def ^{:stratum 0} context-overflow-by-usage?
  "Alias of `prompts/context-overflow-by-usage?`."
  prompts/context-overflow-by-usage?)

(def ^{:stratum 0} context-overflow-error-type
  "Alias of `prompts/context-overflow-error-type`."
  prompts/context-overflow-error-type)

(def ^{:stratum 0} cursor-args
  "Alias of `cli-args/cursor-args`."
  cli-args/cursor-args)

(def ^{:stratum 0} echo-args
  "Alias of `cli-args/echo-args`."
  cli-args/echo-args)

(def ^{:stratum 0} fold-message-usage
  "Alias of `cli-output/fold-message-usage`."
  cli-output/fold-message-usage)

(def ^{:stratum 0} gemini-request-body
  "Alias of `http-wire/gemini-request-body`."
  http-wire/gemini-request-body)

(def ^{:stratum 0} http-complete
  "Alias of `http-backend/http-complete`."
  http-backend/http-complete)

(def ^{:stratum 0} http-post-request
  "Alias of `http-backend/http-post-request`."
  http-backend/http-post-request)

(def ^{:stratum 0} llm-error
  "Alias of `responses/llm-error`."
  responses/llm-error)

(def ^{:stratum 0} log-streaming-result
  "Alias of `cli-output/log-streaming-result`."
  cli-output/log-streaming-result)

(def ^{:stratum 0} mock-exec-fn
  "Alias of `completion/mock-exec-fn`."
  completion/mock-exec-fn)

(def ^{:stratum 0} mock-exec-fn-multi
  "Alias of `completion/mock-exec-fn-multi`."
  completion/mock-exec-fn-multi)

(def ^{:stratum 0} normalize-exec-fn
  "Alias of `completion/normalize-exec-fn`."
  completion/normalize-exec-fn)

(def ^{:stratum 0} ollama-request-body
  "Alias of `http-wire/ollama-request-body`."
  http-wire/ollama-request-body)

(def ^{:stratum 0} openai-request-body
  "Alias of `http-wire/openai-request-body`."
  http-wire/openai-request-body)

(def ^{:stratum 0} opencode-args
  "Alias of `cli-args/opencode-args`."
  cli-args/opencode-args)

(def ^{:stratum 0} openrouter-request-body
  "Alias of `http-wire/openrouter-request-body`."
  http-wire/openrouter-request-body)

(def ^{:stratum 0} parse-claude-stream-line
  "Alias of `cli-output/parse-claude-stream-line`."
  cli-output/parse-claude-stream-line)

(def ^{:stratum 0} parse-cli-output
  "Alias of `cli-output/parse-cli-output`."
  cli-output/parse-cli-output)

(def ^{:stratum 0} parse-codex-stream-line
  "Alias of `cli-output/parse-codex-stream-line`."
  cli-output/parse-codex-stream-line)

(def ^{:stratum 0} parse-ollama-response
  "Alias of `http-wire/parse-ollama-response`."
  http-wire/parse-ollama-response)

(def ^{:stratum 0} parsed-usage
  "Alias of `cli-output/parsed-usage`."
  cli-output/parsed-usage)

(def ^{:stratum 0} post-kill-join-timeout-ms
  "Alias of `stream-exec/post-kill-join-timeout-ms`."
  stream-exec/post-kill-join-timeout-ms)

(def ^{:stratum 0} probe-endpoint-for
  "Alias of `backend-registry/probe-endpoint-for`."
  backend-registry/probe-endpoint-for)

(def ^{:stratum 0} process-stream-lines
  "Alias of `stream-exec/process-stream-lines`."
  stream-exec/process-stream-lines)

(def ^{:stratum 0} prompt-size-telemetry
  "Alias of `prompts/prompt-size-telemetry`."
  prompts/prompt-size-telemetry)

(def ^{:stratum 0} rate-limited?
  "Alias of `cli-output/rate-limited?`."
  cli-output/rate-limited?)

(def ^{:stratum 0} read-process-stream!
  "Alias of `subprocess/read-process-stream!`."
  subprocess/read-process-stream!)

(def ^{:stratum 0} record-parsed-progress!
  "Alias of `cli-output/record-parsed-progress!`."
  cli-output/record-parsed-progress!)

(def ^{:stratum 0} resolve-prompt-via
  "Alias of `prompts/resolve-prompt-via`."
  prompts/resolve-prompt-via)

(def ^{:stratum 0} start-capture-process!
  "Alias of `subprocess/start-capture-process!`."
  subprocess/start-capture-process!)

(def ^{:stratum 0} start-stream-reader!
  "Alias of `stream-reader/start-stream-reader!`."
  stream-reader/start-stream-reader!)

(def ^{:stratum 0} stop-stream-reader!
  "Alias of `stream-reader/stop-stream-reader!`."
  stream-reader/stop-stream-reader!)

(def ^{:stratum 0} stream-exec-fn
  "Alias of `stream-exec/stream-exec-fn`."
  stream-exec/stream-exec-fn)

(def ^{:stratum 0} stream-with-parser
  "Alias of `cli-output/stream-with-parser`."
  cli-output/stream-with-parser)

(def ^{:stratum 0} streaming-error-response
  "Alias of `cli-output/streaming-error-response`."
  cli-output/streaming-error-response)

(def ^{:stratum 0} streaming-success-response
  "Alias of `cli-output/streaming-success-response`."
  cli-output/streaming-success-response)

(def ^{:stratum 0} success-response
  "Alias of `cli-output/success-response`."
  cli-output/success-response)

(def ^{:stratum 0} terminated-by
  "Alias of `cli-output/terminated-by`."
  cli-output/terminated-by)

(def ^{:stratum 0} timeout-result
  "Alias of `stream-exec/timeout-result`."
  stream-exec/timeout-result)

(def ^{:stratum 0} total-input-tokens
  "Alias of `prompts/total-input-tokens`."
  prompts/total-input-tokens)

(def ^{:stratum 0} wait-for-stream-process
  "Alias of `subprocess/wait-for-stream-process`."
  subprocess/wait-for-stream-process)

;------------------------------------------------------------------------------ Layer 1

;; Stays here, calling wait-for-stream-process and
;; post-kill-join-timeout-ms through the aliases above, so a test
;; that redefines llm-client/wait-for-stream-process or
;; llm-client/post-kill-join-timeout-ms still steers it.
(defn- ^{:stratum 1} stop-capture-process!
  "Wait up to `timeout-ms` for the capture subprocess to exit and collect
   its output. On timeout the process tree is destroyed, not just the CLI
   (its MCP servers and tool children would otherwise keep running), and
   the call waits again briefly. If the waiting thread is interrupted or
   anything else throws, the tree is destroyed before the throwable
   propagates; on interrupt the flag the exception cleared is restored
   first."
  [{:keys [^Process process stdin-thread stdout-thread stderr-thread stdout stderr]}
   timeout-ms]
  (try+
    (let [initial-result (wait-for-stream-process process timeout-ms)
          timed-out? (= -1 (:exit initial-result))
          result (if timed-out?
                   (do
                     (subprocess/destroy-process-tree! process)
                     (wait-for-stream-process process (post-kill-join-timeout-ms)))
                   initial-result)]
      (doseq [thread [stdin-thread stdout-thread stderr-thread]
              :when thread]
        (.join ^Thread thread subprocess/stream-reader-join-timeout-ms))
      {:out (or @stdout "")
       :err (or (not-empty (:err result)) @stderr "")
       :exit (:exit result)})
    (catch InterruptedException _
      (subprocess/destroy-process-tree! process)
      (.interrupt (Thread/currentThread))
      (throw+))
    ;; `Object` also matches ex-info, which a `Throwable` selector misses.
    (catch Object _
      (subprocess/destroy-process-tree! process)
      (throw+))))

(defn ^{:stratum 1} http-stream-complete
  "Complete request using HTTP API with streaming.

   Note: Currently falls back to non-streaming — the HTTP backends
   run behind the async job seam, so chunk-level streaming buys no
   user-visible latency there."
  [backend-config request on-chunk config]
  (let [accumulated (atom "")]
    (try
      (let [result (http-complete backend-config
                                  (assoc request :streaming? false)
                                  config)]
        (when (:success result)
          (let [content (:content result)]
            (reset! accumulated content)
            (on-chunk {:delta content :done? true :content content})))
        result)
      (catch Exception e
        (llm-error :anomalies/fault "stream_error"
                   (msg/t :http-provider.system/stream-failed
                          {:reason (.getMessage e)}))))))

(defn ^{:stratum 1} handle-non-streaming-fallback [client request on-chunk]
  (let [result (complete-impl client request)]
    (when (:success result)
      (on-chunk {:delta (:content result)
                 :done? true
                 :content (:content result)}))
    result))

(defn ^{:stratum 1} handle-streaming [client request on-chunk backend-config progress-monitor]
  (let [{:keys [logger config]} client
        stream-fn (or (:stream-exec-fn client) stream-exec-fn)
        {:keys [backend model]} config
        {:keys [cmd args-fn stream-parser]} backend-config
        prompt     (prompts/build-request-prompt request)
        prompt-via (resolve-prompt-via backend-config)
        request-with-model (completion/with-client-config request config)
        args     (args-fn (assoc request-with-model
                                 :prompt prompt
                                 :streaming? true
                                 :prompt-via prompt-via))
        full-cmd (into [cmd] args)
        accumulated-content (atom "")
        accumulated-usage (atom nil)
        accumulated-cost (atom nil)
        accumulated-tools (atom [])
        accumulated-session-id (atom nil)
        accumulated-stop-reason (atom nil)
        accumulated-turns (atom nil)]
    (when logger
      (log/info logger :system :agent/streaming-prompt-sent
                {:data (assoc (prompt-size-telemetry (:system request) prompt)
                              :backend backend
                              :model model)}))
    ;; :backend-key starts the network-drop monitor in stream-exec-fn.
    (let [base-opts (cond-> {:progress-monitor progress-monitor
                             :workdir (:workdir request)}
                      (settings/network-monitor-enabled?) (assoc :backend-key backend))
          stream-opts (merge base-opts
                             (prompts/exec-opts-for-prompt-via prompt-via prompt))
          result (stream-fn
                  full-cmd
                  (stream-with-parser stream-parser on-chunk progress-monitor
                                      accumulated-content accumulated-usage
                                      accumulated-cost accumulated-tools
                                      accumulated-session-id
                                      accumulated-stop-reason accumulated-turns)
                  stream-opts)
          exit-code (:exit result)
          timeout-info (:timeout result)
          final-content @accumulated-content
          raw-output (:out result)
          diagnostic-content (if (str/blank? final-content)
                               raw-output
                               final-content)
          tools @accumulated-tools
          session-id @accumulated-session-id
          stop-reason @accumulated-stop-reason
          num-turns @accumulated-turns
          tool-call-count (count tools)
          final-message-preview (cli-output/message-preview diagnostic-content)
          usage @accumulated-usage
          fallback-response (when (cli-output/blank-streaming-success? exit-code
                                                            final-content
                                                            tool-call-count
                                                            usage)
                              (complete-impl client request))]
      (if fallback-response
        (do
          (when (:success fallback-response)
            (on-chunk {:delta (:content fallback-response)
                       :done? true
                       :content (:content fallback-response)}))
          (when logger
            (log/info logger :system :agent/streaming-fallback
                      {:data {:backend backend
                              :reason :empty-stream-success
                              :stderr (:err result)}}))
          fallback-response)
        (do
          (on-chunk {:delta ""
                     :done? true
                     :content final-content
                     :timeout timeout-info})
          (log-streaming-result logger timeout-info (count final-content) tool-call-count)
          (when (and logger (seq tools))
            (log/info logger :system :agent/tools-called
                      {:data {:tools tools :count (count tools)}}))
          (when (and logger stop-reason)
            (log/info logger :system :agent/stop-reason
                      {:data {:stop-reason stop-reason :num-turns num-turns
                              :content-length (count final-content)}}))
          ;; Cost fallback: some backends (e.g. older Claude Code
          ;; CLI versions, codex stream parser) don't surface
          ;; total_cost_usd in the result frame, so
          ;; @accumulated-cost stays nil and the runner banner
          ;; reports $0.0000 despite real backend costs. When
          ;; cost is nil but usage carries tokens, compute the
          ;; fallback estimate from the model + usage via
          ;; cost/estimate-cost. Models without pricing in the
          ;; table return 0.0 (no false-positive cost), so this
          ;; is a non-decreasing fix — never produces a wrong-
          ;; direction value.
          (let [resolved-cost (or @accumulated-cost
                                  (cost/estimate-cost usage model))]
            (if (zero? exit-code)
              (cond-> (streaming-success-response final-content exit-code
                                                  usage resolved-cost
                                                  stop-reason num-turns
                                                  tool-call-count final-message-preview
                                                  (:err result))
                (seq tools) (assoc :tools-called tools)
                session-id  (assoc :session-id session-id))
              ;; A run that was cut (timeout) or died still consumed
              ;; tokens and made tool calls; carry them so the role's
              ;; bookkeeping (`implementer/llm-called`) reports what
              ;; actually happened instead of `:tokens 0 :tools-called []`
              ;; (2026-09-04 trap-bench series 9).
              (cond-> (streaming-error-response final-content exit-code (:err result)
                                                diagnostic-content
                                                timeout-info stop-reason num-turns
                                                tool-call-count final-message-preview
                                                usage
                                                ;; effective model actually invoked (honors per-request :model)
                                                (model-registry/context-window-for-model-id
                                                 (:model request-with-model)))
                (seq tools)   (assoc :tools-called tools)
                ;; Cost is known when the backend reported one
                ;; (total_cost_usd) or usage lets us estimate it —
                ;; same two sources the success branch draws on.
                (or (some? @accumulated-cost) (some? usage))
                (assoc :cost-usd resolved-cost)
                session-id    (assoc :session-id session-id)))))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} default-exec-fn
  "Run `cmd` with ProcessBuilder, capturing :out / :err / :exit.

   2-arity opts map supports:
   - `:stdin`  — string piped to the subprocess's stdin
   - `:workdir` — directory where the subprocess runs
   - `:env`    — entries added to the agent environment"
  ([cmd] (default-exec-fn cmd {}))
  ([cmd {:keys [stdin workdir env]}]
   (let [timeout-ms 600000
         process (start-capture-process! cmd {:stdin stdin
                                              :workdir workdir
                                              :env env})]
     (stop-capture-process! process timeout-ms))))

(defn ^{:stratum 2} complete-stream-impl [client request on-chunk]
  (let [{:keys [config]} client
        {:keys [backend]} config
        backend-config (get backends backend)
        {:keys [streaming? cmd]} backend-config
        progress-monitor (or (:progress-monitor request)
                             (pm/create-progress-monitor
                              {:stagnation-threshold-ms (stream-exec/default-stagnation-threshold-ms)
                               :max-total-ms (stream-exec/default-max-total-ms)}))]

    ;; Handle HTTP backends
    (if (= cmd "http")
      (http-stream-complete backend-config
                            (completion/with-client-config request config)
                            on-chunk
                            config)

      ;; CLI backends
      (if-not streaming?
        (handle-non-streaming-fallback client request on-chunk)
        (handle-streaming client request on-chunk backend-config progress-monitor)))))
