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

(ns ai.miniforge.llm.network-health
  "Provider-specific network health probe.

   Detects connectivity loss to the upstream LLM provider before the
   per-phase progress monitor's stagnation timer would (2-3 minutes of
   dead air). The 2026-06-04 eliminate-requiring-resolve dogfood post-
   mortem identified network drops as an undetected failure mode — the
   CLI agent waits on socket I/O indefinitely when its provider goes
   away, looking identical to a model 'thinking' until the slow
   stagnation cutoff fires.

   This ns provides the detection primitive. PR-B will schedule it
   alongside the progress monitor and emit a `:workflow/network-drop`
   event on a confirmed drop; PR-C will auto-resume the workflow from
   the last persisted checkpoint."
  (:require [org.httpkit.client :as http]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:const default-probe-timeout-ms
  "Maximum time a single probe is allowed to wait for an HTTP response
   before being considered failed. 3s is a generous ceiling — a healthy
   provider responds in <500ms; a 3s ceiling tolerates a slow CDN edge
   without making the probe itself a stagnation source."
  3000)

(def ^:const generic-connectivity-probe-url
  "Fallback probe URL when a backend has no provider-specific endpoint
   wired up (e.g. test backends, or future backends added without a
   probe-endpoints entry). Cloudflare's public DNS endpoint is a
   well-known low-latency connectivity check that does not bias the
   result toward any single LLM provider."
  "https://1.1.1.1/")

(def probe-endpoints
  "Probe URL keyed by `:llm/backend`. Each URL is a stable endpoint at
   the provider's edge that responds to a HEAD with anything (200, 405,
   even 4xx) — we only care that the connection completed, not the
   response status, so any HTTP response proves connectivity.

   For backends without a provider-controlled endpoint (`:echo`, future
   custom backends), callers fall through to
   `generic-connectivity-probe-url`."
  {:claude   "https://api.anthropic.com/"
   :codex    "https://api.openai.com/"
   :opencode "https://api.anthropic.com/"           ; OpenCode's typical
                                                    ; default provider; tunable
                                                    ; per-deployment in PR-B.
   :cursor   "https://api2.cursor.sh/"
   :ollama   "http://localhost:11434/api/version"
   :echo     generic-connectivity-probe-url})

(defn endpoint-for
  "Resolve the probe URL for `backend-key`. Returns the provider-
   specific URL when known, the generic Cloudflare DNS connectivity
   check otherwise."
  [backend-key]
  (get probe-endpoints backend-key generic-connectivity-probe-url))

;------------------------------------------------------------------------------ Layer 1
;; Probe primitive

(defn- response-proves-connectivity?
  "True when an http-kit response map shows the request reached an HTTP
   peer and got a response back — regardless of status code. A 4xx or
   even a 5xx means the network is up; only an exception (`:error`) or
   a missing `:status` indicates connectivity failure."
  [response]
  (boolean (and (map? response)
                (nil? (:error response))
                (pos-int? (:status response)))))

(defn network-healthy?
  "Probe the network path to `backend-key`'s upstream provider.

   Returns true when the probe receives an HTTP response within
   `:timeout-ms` (any status code counts — a 4xx still proves the
   network reached the server). Returns false on connection failure
   (DNS, unreachable host, refused connection) or probe timeout.

   Options (`opts`):
   - `:timeout-ms` — per-probe deadline; defaults to
     `default-probe-timeout-ms`.
   - `:http-client` — override for tests; expects an http-kit-shaped
     function `(fn [request-map] (promise/deref-able))`. Defaults to
     `org.httpkit.client/request`.

   The function intentionally has no side effects beyond the HTTP
   request — no logging, no event emission, no telemetry. PR-B layers
   those on top so the primitive stays cheap to call from the
   scheduler loop."
  ([backend-key]
   (network-healthy? backend-key {}))
  ([backend-key {:keys [timeout-ms http-client]
                 :or   {timeout-ms  default-probe-timeout-ms
                        http-client http/request}}]
   (let [endpoint (endpoint-for backend-key)
         response @(http-client
                    {:url     endpoint
                     :method  :head
                     :timeout timeout-ms
                     :as      :text})]
     (response-proves-connectivity? response))))
