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
   probe).

   The agent CLI reaches its provider through the proxy named in the
   standard proxy environment variables, so the probe goes through the
   same proxy (`proxy-request-opts`). A probe that went direct would
   fail on every attempt behind a proxy, and the monitor would kill a
   healthy agent."
  (:require [clojure.string :as str]
            [org.httpkit.client :as http]))

;------------------------------------------------------------------------------ Layer 0

;; Constants
(def ^{:stratum 0} ^:const default-probe-timeout-ms
  "Maximum time a single probe is allowed to wait for an HTTP response
   before being considered failed. 3s is a generous ceiling — a healthy
   provider responds in <500ms; a 3s ceiling tolerates a slow CDN edge
   without making the probe itself a stagnation source."
  3000)

(def ^{:stratum 0} ^:private https-proxy-env-vars
  "Where the proxy for an https URL is looked up, first non-blank wins:
   lowercase before uppercase, then the plain-http proxy. This is the
   order of Node's EnvHttpProxyAgent; the Claude CLI runs on Node."
  ["https_proxy" "HTTPS_PROXY" "http_proxy" "HTTP_PROXY"])

(def ^{:stratum 0} ^:private http-proxy-env-vars
  "Where the proxy for an http URL is looked up, first non-blank wins."
  ["http_proxy" "HTTP_PROXY"])

(def ^{:stratum 0} ^:private no-proxy-env-vars
  "Where the proxy exemption list is looked up, first non-blank wins."
  ["no_proxy" "NO_PROXY"])

(def ^{:stratum 0} ^:private default-ports
  "Port implied by a URL scheme when the URL names none."
  {"http" 80
   "https" 443})

;; Proxy selection
(defn- ^{:stratum 0} env-value
  "First non-blank value in `env` among the variable `names`."
  [env names]
  (some (fn [k] (some-> (get env k) str/trim not-empty)) names))

(defn- ^{:stratum 0} no-proxy-token-matches?
  "Node's NO_PROXY rule for one `host` or `host:port` token: a token
   that starts with `.` or `*` matches any host ending in it (the `*`
   dropped, so a bare `*` matches every host); any other token matches
   only that exact host. A port on the token must equal `port`."
  [host port token]
  (let [[_ host-part port-part] (or (re-matches #"(.+):(\d+)" token)
                                    [token token nil])
        token-host (str/lower-case host-part)]
    (and (or (nil? port-part) (= (parse-long port-part) port))
         (if (re-find #"^[.*]" token-host)
           (str/ends-with? host (str/replace token-host #"^\*" ""))
           (= host token-host)))))

(defn- ^{:stratum 0} with-proxy-scheme
  "A proxy given as `host:port` is an http proxy, as curl and Node read it."
  [proxy]
  (if (str/includes? proxy "://")
    proxy
    (str "http://" proxy)))

(def ^{:stratum 0} ^:private proxy-auth-required-status
  "407 answer to a CONNECT: the proxy wants credentials, which the probe
   does not send (the agent CLI may hold them). It proves the proxy is
   reachable, so it counts as healthy rather than as a drop."
  407)

(defn- ^{:stratum 0} success-status?
  [status]
  (<= 200 status 299))

;------------------------------------------------------------------------------ Layer 1

;; Probe primitive
(defn- ^{:stratum 1} response-proves-connectivity?
  "True when an http-kit response map shows the request reached an HTTP
   peer and got a response back. From the origin (a direct probe, or an
   http probe relayed by a proxy) any status counts: a 4xx or even a 5xx
   means the network is up. A `tunneled?` probe's response is the
   proxy's answer to CONNECT, so only 2xx (tunnel open) or 407 counts; a
   502/503/504 there means the proxy could not reach the provider. An
   exception (`:error`) or a missing `:status` is always a failure."
  [response tunneled?]
  (let [status (:status response)]
    (boolean (and (map? response)
                  (nil? (:error response))
                  (pos-int? status)
                  (or (not tunneled?)
                      (success-status? status)
                      (= proxy-auth-required-status status))))))

(defn ^{:stratum 1} proxy-request-opts
  "http-kit request options that send a probe of `probe-url` through the
   proxy the agent CLI would use, read from `env` (a map of environment
   variable names to values). Returns {} when no proxy applies: none is
   set for the URL's scheme, or NO_PROXY exempts its host.

   An https URL goes through a CONNECT tunnel (`:tunnel? true`); the
   proxy's answer to the CONNECT is the probe's response. http-kit writes
   the CONNECT target from the URL and leaves out a default port, which
   proxies reject, so the returned `:url` names the port. An http URL is
   sent to the proxy as an absolute URI.

   Keep-alive is off (`:keepalive -1`): after a CONNECT the connection is
   a tunnel to the provider, and http-kit would otherwise reuse it to
   send the next probe's CONNECT into that tunnel."
  [probe-url env]
  (let [uri (java.net.URI. probe-url)
        scheme (some-> (.getScheme uri) str/lower-case)
        host (some-> (.getHost uri) str/lower-case)
        port (if (neg? (.getPort uri)) (get default-ports scheme) (.getPort uri))
        proxy (env-value env (if (= "https" scheme)
                               https-proxy-env-vars
                               http-proxy-env-vars))
        no-proxy-tokens (some-> (env-value env no-proxy-env-vars)
                                (str/split #"[,\s]+"))
        exempt? (and host (some #(no-proxy-token-matches? host port %)
                                no-proxy-tokens))
        query (some->> (.getRawQuery uri) (str "?"))]
    (if (or (nil? proxy) (nil? host) (nil? port) exempt?)
      {}
      {:url (str scheme "://" host ":" port (.getRawPath uri) query)
       :proxy-url (with-proxy-scheme proxy)
       :tunnel? (= "https" scheme)
       :keepalive -1})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} network-healthy?
  "Probe `probe-url` for a live HTTP response and return true/false.

   Returns true when the probe receives an HTTP response within
   `:timeout-ms` (any status code from the origin counts — a 4xx still
   proves the network reached the server; through a CONNECT tunnel only
   2xx or 407, see `response-proves-connectivity?`). Returns false on
   any failure mode:
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
   - `:env`         — environment variables the proxy is read from
     (see `proxy-request-opts`); defaults to `(System/getenv)`.

   The function intentionally has no side effects beyond the HTTP
   request — no logging, no event emission, no telemetry. PR-B layers
   those on top so the primitive stays cheap to call."
  ([probe-url]
   (network-healthy? probe-url {}))
  ([probe-url {:keys [timeout-ms http-client env]
               :or   {timeout-ms  default-probe-timeout-ms
                      http-client http/request
                      env         (System/getenv)}}]
   (try
     (let [request (merge {:url     probe-url
                           :method  :head
                           :timeout timeout-ms
                           :as      :text}
                          (proxy-request-opts probe-url env))
           response @(http-client request)]
       (response-proves-connectivity? response (boolean (:tunnel? request))))
     (catch Exception _
       ;; http-kit can also surface connection failures as a thrown
       ;; exception (see `llm_client/http-post-request` comments).
       ;; Swallow both shapes here so the scheduler loop never crashes
       ;; on a probe.
       false))))
