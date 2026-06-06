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
  "URL-driven network connectivity probe.

   Detects connectivity loss to an upstream HTTP endpoint before the
   per-phase progress monitor's stagnation timer would (2-3 minutes of
   dead air). The 2026-06-04 eliminate-requiring-resolve dogfood post-
   mortem identified network drops as an undetected failure mode — a
   CLI agent waits on socket I/O indefinitely when its provider goes
   away, looking identical to a model 'thinking' until the slow
   stagnation cutoff fires.

   This namespace is intentionally backend-agnostic. The caller (e.g.
   `stream-exec-fn` in PR-B) resolves the provider-specific probe URL
   from its backend config (`backends/probe-endpoint-for`) and passes
   that URL here. Keeping endpoint resolution out of this ns avoids a
   require cycle with `llm-client` (which carries the backend config
   and itself depends on the network monitor that schedules this
   probe)."
  (:require [org.httpkit.client :as http]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:const default-probe-timeout-ms
  "Maximum time a single probe is allowed to wait for an HTTP response
   before being considered failed. 3s is a generous ceiling — a healthy
   provider responds in <500ms; a 3s ceiling tolerates a slow CDN edge
   without making the probe itself a stagnation source."
  3000)

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
  "Probe `probe-url` for a live HTTP response and return true/false.

   Returns true when the probe receives an HTTP response within
   `:timeout-ms` (any status code counts — a 4xx still proves the
   network reached the server). Returns false on any failure mode:
   - http-kit's response carries `:error` (DNS failure, connection
     refused, timeout, etc.).
   - The request map promise is missing `:status` for any reason.
   - The http call itself throws or the `@` deref throws (http-kit can
     surface failures via either channel; both must be treated as
     `down` so the probe stays safe to call from the PR-B scheduler
     loop).

   Options (`opts`):
   - `:timeout-ms`  — per-probe deadline; defaults to
     `default-probe-timeout-ms`.
   - `:http-client` — override for tests; expects an http-kit-shaped
     function `(fn [request-map] (promise/deref-able))`. Defaults to
     `org.httpkit.client/request`.

   The function intentionally has no side effects beyond the HTTP
   request — no logging, no event emission, no telemetry. PR-B layers
   those on top so the primitive stays cheap to call."
  ([probe-url]
   (network-healthy? probe-url {}))
  ([probe-url {:keys [timeout-ms http-client]
               :or   {timeout-ms  default-probe-timeout-ms
                      http-client http/request}}]
   (try
     (let [response @(http-client
                       {:url     probe-url
                        :method  :head
                        :timeout timeout-ms
                        :as      :text})]
       (response-proves-connectivity? response))
     (catch Exception _
       ;; http-kit can also surface connection failures as a thrown
       ;; exception (see `llm_client/http-post-request` comments).
       ;; Swallow both shapes here so the scheduler loop never crashes
       ;; on a probe.
       false))))
