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
(ns ai.miniforge.llm.protocols.impl.llm-client.completion
  "Non-streaming completion: `complete-impl`, its request and response
   logging, and the exec-fn adapters (the arity wrapper, the capsule
   router, the mocks)."
  (:require
   [ai.miniforge.llm.messages :as msg]
   [ai.miniforge.llm.protocols.impl.llm-client.backend-registry :as backend-registry]
   [ai.miniforge.llm.protocols.impl.llm-client.cli-output :as cli-output]
   [ai.miniforge.llm.protocols.impl.llm-client.http-backend :as http-backend]
   [ai.miniforge.llm.protocols.impl.llm-client.prompts :as prompts]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} with-client-config
  "`request` with the client's per-call settings: the model, the Ollama
   context size, and isolation. A client created `:isolated?` isolates
   every call; a request may also ask for it alone."
  [request {:keys [model num-ctx isolated?]}]
  (cond-> request
    model (assoc :model model)
    num-ctx (assoc :num-ctx num-ctx)
    isolated? (assoc :isolated? true)))

(defn- ^{:stratum 0} run-cli-exec
  "Invoke a CLI exec-fn with cmd plus optional opts. The 1-arity
   branch keeps caller-supplied exec-fns from the days before opts
   existed working unchanged; the 2-arity branch threads opts in
   when they carry signal (e.g. :stdin)."
  [exec-fn cmd opts]
  (if (seq opts)
    (exec-fn cmd opts)
    (exec-fn cmd)))

(defn ^{:stratum 0} normalize-exec-fn
  "Wrap an exec-fn so it accepts both 1-arity (cmd) and 2-arity
   (cmd opts) call patterns.

   `:exec-fn` on `create-client` is a documented public test hook.
   Pre-:prompt-via callers supplied 1-arity functions; once the
   :stdin path landed the impl started invoking exec-fn 2-arity for
   the :claude backend. Without this wrapper a 1-arity user fn
   would throw ArityException the first time the new code path
   ran.

   The wrapper probes 2-arity on first 2-arity call and caches the
   decision in an atom so subsequent calls dispatch directly. The
   probe only happens when the caller actually passes opts; pure
   1-arity invocations stay one indirection.

   Returns a fn equivalent to `f` for callers that already accept
   both arities."
  [f]
  (let [arity (atom :unknown)]
    (fn
      ([cmd] (f cmd))
      ([cmd opts]
       (case @arity
         :one (f cmd)
         :two (f cmd opts)
         (try
           (let [r (f cmd opts)]
             (reset! arity :two)
             r)
           (catch clojure.lang.ArityException _
             (reset! arity :one)
             (f cmd))))))))

(defn ^{:stratum 0} log-prompt-sent [logger backend prompt]
  (when logger
    (log/debug logger :system :agent/prompt-sent
               {:data {:backend backend
                       :prompt-length (count prompt)}})))

(defn ^{:stratum 0} log-response [logger response]
  (when logger
    (if (:success response)
      (log/debug logger :system :agent/response-received
                 {:data {:response-length (count (:content response))}})
      (log/error logger :system :agent/task-failed
                 {:message (msg/t :backend.system/request-failed)
                  :data (:error response)}))))

(defn ^{:stratum 0} capsule-exec-fn
  "Returns an exec-fn that routes CLI commands through a task capsule executor.
   Used in governed mode so the agent CLI runs inside the Docker/K8s container
   instead of on the host (N11 §6.2).

   Arguments:
   - execute-fn  - function [executor env-id command opts] -> result map
   - executor    - TaskExecutor instance
   - env-id      - environment/container ID
   - workdir     - workspace directory inside capsule"
  [execute-fn executor env-id workdir]
  (fn [cmd]
    (let [result (execute-fn executor env-id cmd {:workdir workdir})]
      (if (and (map? result) (:data result))
        {:out (get-in result [:data :stdout] "")
         :err (get-in result [:data :stderr] "")
         :exit (get-in result [:data :exit-code] 0)}
        {:out ""
         :err (str "Capsule exec error: " result)
         :exit 1}))))

(defn ^{:stratum 0} mock-exec-fn [output & {:keys [exit] :or {exit 0}}]
  (fn
    ([_cmd]      {:out output :err "" :exit exit})
    ([_cmd _opts] {:out output :err "" :exit exit})))

(defn ^{:stratum 0} mock-exec-fn-multi [outputs]
  (let [call-count (atom 0)
        respond    (fn []
                     (let [idx @call-count
                           output (get outputs idx (last outputs))]
                       (swap! call-count inc)
                       {:out output :err "" :exit 0}))]
    (fn
      ([_cmd]       (respond))
      ([_cmd _opts] (respond)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} complete-impl [client request]
  (let [{:keys [config logger exec-fn]} client
        {:keys [backend]} config
        backend-config (get backend-registry/backends backend)
        {:keys [cmd args-fn env-fn]} backend-config
        request-with-model (with-client-config request config)]
    (log-prompt-sent logger backend (prompts/build-request-prompt request))

    ;; Handle HTTP backends differently from CLI backends
    (if (= cmd "http")
      (let [response (http-backend/http-complete backend-config request-with-model config)]
        (log-response logger response)
        response)

      ;; CLI backend
      (let [prompt     (prompts/build-request-prompt request)
            prompt-via (prompts/resolve-prompt-via backend-config)
            args     (args-fn (assoc request-with-model
                                     :prompt prompt
                                     :prompt-via prompt-via))
            full-cmd (into [cmd] args)
            opts     (cond-> (prompts/exec-opts-for-prompt-via prompt-via prompt)
                       (:workdir request) (assoc :workdir (:workdir request))
                       env-fn             (assoc :env (env-fn request-with-model)))
            result   (run-cli-exec exec-fn full-cmd opts)
            response (cli-output/parse-cli-output (:out result) (:exit result) (:err result))]
        (log-response logger response)
        response))))
