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
(ns ai.miniforge.llm.protocols.impl.llm-client.http-backend
  "The direct HTTP backends: the POST transport, API-key and endpoint
   resolution, auth headers, the provider registry, and `http-complete`."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [org.httpkit.client :as http]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.llm.messages :as msg]
   [ai.miniforge.llm.protocols.impl.llm-client.http-wire :as http-wire]
   [ai.miniforge.llm.protocols.impl.llm-client.responses :as responses]
   [ai.miniforge.llm.protocols.impl.llm-client.settings :as settings]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} http-failure-anomaly
  "Build the canonical `:unavailable` anomaly that this brick emits for
   any HTTP-layer failure on an LLM call (thrown exception or http-kit
   `{:error <Throwable>}` deref). Centralizes the shape so the throwing
   and non-throwing failure modes converge."
  [^Throwable cause url]
  (anomaly/anomaly
   :unavailable
   (str "HTTP request failed: " (.getMessage cause))
   {:operation :http-request
    :url url}))

(defn- ^{:stratum 0} resolve-api-key
  "Resolve the API key for a direct provider backend: a non-blank
   `:api-key` in the client config wins; otherwise the backend's
   `:api-key-env` environment variable. Blank/whitespace values count
   as absent on BOTH paths — a config map carrying :api-key \"\" (BYOK
   setups where the key field exists but is empty) must still fall
   through to the env var. The env path is how the sandboxed app
   delivers a keychain-held key to the workflow process without it
   ever touching disk."
  [backend-config config]
  (or (some-> (:api-key config) str str/trim not-empty)
      (some-> (:api-key-env backend-config) settings/getenv-value str str/trim not-empty)))

(defn- ^{:stratum 0} resolve-base-url
  "The endpoint for the request. Only a backend that declares
   `:base-url-env` supports overrides — there a non-blank client-config
   `:base-url` wins, then the env variable. Every other backend always
   uses its static `:api-endpoint`, so a stray client `:base-url` can
   never redirect a fixed-endpoint provider."
  [backend-config config]
  (or (when (:base-url-env backend-config)
        (or (some-> (:base-url config) str str/trim not-empty)
            (some-> (:base-url-env backend-config) settings/getenv-value str str/trim
                    not-empty)))
      (:api-endpoint backend-config)))

(defn- ^{:stratum 0} anthropic-api-version
  []
  (settings/client-default [:http :anthropic :api-version]))

(defn- ^{:stratum 0} http-idle-timeout-ms
  "How long a direct provider call may go without a byte from the
   server before the client gives up."
  []
  (settings/client-default [:http :idle-timeout-ms]))

(def ^{:stratum 0} ^:private http-providers
  "Wire-shape registry for the HTTP backends: request-body builder +
   response parser per provider string, plus `:requires-model?` for
   the APIs that embed the model in the body or URL and have no
   default. `http-complete` dispatches here; a provider missing from
   this map is an unsupported HTTP backend."
  {"Ollama"    {:body-fn http-wire/ollama-request-body
                :parse-fn http-wire/parse-ollama-response}
   "Anthropic" {:body-fn http-wire/anthropic-request-body
                :parse-fn (partial http-wire/parse-provider-response http-wire/extract-anthropic)
                :requires-model? true}
   "OpenAI"    {:body-fn http-wire/openai-request-body
                :parse-fn (partial http-wire/parse-provider-response http-wire/extract-openai)
                :requires-model? true}
   "OpenAI-Compatible" {:body-fn http-wire/openai-request-body
                        :parse-fn (partial http-wire/parse-provider-response
                                           http-wire/extract-openai)
                        :requires-model? true}
   "OpenRouter" {:body-fn http-wire/openrouter-request-body
                 :parse-fn (partial http-wire/parse-provider-response
                                    http-wire/extract-openrouter)
                 :requires-model? true}
   "Gemini"    {:body-fn http-wire/gemini-request-body
                :parse-fn (partial http-wire/parse-provider-response http-wire/extract-gemini)
                :requires-model? true}})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} request-endpoint
  "Resolve the request URL. Gemini embeds the model in the URL path
   (`:model-in-url?`); the other providers take it in the body."
  [backend-config request config]
  (let [endpoint (resolve-base-url backend-config config)]
    (if (:model-in-url? backend-config)
      (format endpoint (:model request))
      endpoint)))

(defn ^{:stratum 1} http-post-request
  "Make HTTP POST request to LLM API.

   Returns HTTP response map or canonical anomaly map on failure
   (W2 convergence: `:anomaly/type :unavailable`, no subtype since
   `:anomalies/unavailable` is a generic-standard category).

   http-kit signals connection failures via either a thrown exception
   or a `{:error <Throwable>}` response map (depending on whether the
   error surfaces before or after the future resolves); both modes
   collapse to the same canonical anomaly.

   The idle timeout is set explicitly: these calls are non-streaming, so
   the server sends nothing until the model is done, and a long
   completion outlasts http-kit's 60 s default."
  [url headers body]
  (try
    (let [response @(http/post url
                               {:headers headers
                                :body (json/generate-string body)
                                :idle-timeout (http-idle-timeout-ms)})]
      (if-let [cause (:error response)]
        (http-failure-anomaly cause url)
        response))
    (catch Exception e
      (http-failure-anomaly e url))))

(defn- ^{:stratum 1} provider-headers
  "Auth + protocol headers for a direct provider request. Each provider
   spells the API-key header differently; only Anthropic versions its
   wire protocol via a header. The default arm covers Ollama, which is
   local and credential-free."
  [provider api-key]
  (case provider
    "Anthropic" {"Content-Type" "application/json"
                 "x-api-key" api-key
                 "anthropic-version" (anthropic-api-version)}
    "OpenAI"    {"Content-Type" "application/json"
                 "Authorization" (str "Bearer " api-key)}
    "OpenRouter" {"Content-Type" "application/json"
                  "Authorization" (str "Bearer " api-key)}
    ;; Compatible servers are usually credential-free; send auth only
    ;; when a key was actually supplied (vLLM behind a gateway, etc.).
    "OpenAI-Compatible" (cond-> {"Content-Type" "application/json"}
                          (some? api-key)
                          (assoc "Authorization" (str "Bearer " api-key)))
    "Gemini"    {"Content-Type" "application/json"
                 "x-goog-api-key" api-key}
    {"Content-Type" "application/json"}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} http-complete
  "Complete request using an HTTP backend.

   Three families share this path: the local, credential-free Ollama
   endpoint; the direct API-key providers (:anthropic-api /
   :openai-api / :gemini-api / :openrouter) for builds where CLI-agent
   backends are unavailable; and :openai-compat, any server speaking
   the OpenAI wire shape, keyed or not. `config` is the client config — only `:api-key` is
   read from it here; the caller (`complete-impl` /
   `complete-stream-impl`) has already merged the config `:model` into
   `request`, which is where this fn and the body builders read it."
  [backend-config request config]
  (let [{:keys [provider api-key-env]} backend-config
        {:keys [body-fn parse-fn requires-model?] :as provider-entry}
        (get http-providers provider)
        api-key (resolve-api-key backend-config config)]
    (cond
      (nil? provider-entry)
      (responses/llm-error :anomalies/unsupported "unsupported_backend"
                 (msg/t :http-provider.system/unsupported-backend
                        {:provider provider}))

      (and api-key-env
           (not (:optional-api-key? backend-config))
           (str/blank? (str api-key)))
      (responses/llm-error :anomalies/incorrect "missing_api_key"
                 (msg/t :http-provider.system/missing-api-key
                        {:provider provider :env-var api-key-env}))

      ;; Fail closed before any request rather than formatting a nil
      ;; model into the URL (Gemini) or body (Anthropic/OpenAI).
      (and requires-model? (str/blank? (str (:model request))))
      (responses/llm-error :anomalies/incorrect "missing_model"
                 (msg/t :http-provider.system/missing-model
                        {:provider provider}))

      :else
      (parse-fn (http-post-request (request-endpoint backend-config request
                                                     config)
                                   (provider-headers provider api-key)
                                   (body-fn request))))))
