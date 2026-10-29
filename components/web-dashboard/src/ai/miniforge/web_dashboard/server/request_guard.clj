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
(ns ai.miniforge.web-dashboard.server.request-guard
  "Checks that keep other web pages and other host names out of the
   dashboard, applied to every request before routing.

   A page the operator visits can make the browser send requests to the
   dashboard; DNS rebinding can make a hostile name resolve to 127.0.0.1.
   So:
   - a dashboard bound to loopback answers only to a loopback Host header
     (localhost, 127.0.0.1, [::1]) on its own port;
   - a WebSocket upgrade and every state-changing request with an Origin
     header must come from the dashboard's own origin (or, on a loopback
     bind, a loopback origin on its port);
   - a state-changing request with a text/plain body is refused outright:
     no dashboard client sends one, and it is the body a cross-site page
     can send without a preflight;
   - event ingest (/api/events/ingest, /ws/events) needs this start's
     token in the `X-Miniforge-Ingest-Token` header; the token is in the
     discovery file, readable only by the user who started the dashboard."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} loopback-host-names
  "Host names a loopback-bound dashboard answers to."
  #{"localhost" "127.0.0.1" "[::1]"})

(def ^{:stratum 0} ingest-token-header
  "Request header (lower-cased, as the server presents headers) that
   carries the ingest token."
  "x-miniforge-ingest-token")

(defn ^{:stratum 0} host-authority
  "`[host port]` of a Host header value (`host[:port]`, an IPv6 host in
   brackets), lower-cased; port is nil when absent. Nil when unparseable."
  [value]
  (let [v (str/lower-case (str/trim (str value)))]
    (when-let [[_ host port] (or (re-matches #"(\[[0-9a-f:.]+\])(?::(\d+))?" v)
                                 (re-matches #"([^:\[\]/]+)(?::(\d+))?" v))]
      [host port])))

(defn ^{:stratum 0} origin-authority
  "`[host port]` of an Origin header (`scheme://host[:port]`) for http or
   https, with the scheme's default port filled in. Nil for `null` or
   anything else."
  [origin]
  (try
    (let [uri    (java.net.URI. (str origin))
          scheme (some-> (.getScheme uri) str/lower-case)
          host   (some-> (.getHost uri) str/lower-case)
          port   (.getPort uri)]
      (when (and host (contains? #{"http" "https"} scheme))
        [host (str (if (neg? port) (if (= "https" scheme) 443 80) port))]))
    (catch Exception _ nil)))

(defn ^{:stratum 0} forbidden
  "403 JSON response naming why the request was refused."
  [error]
  {:status  403
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string {:error error})})

(defn ^{:stratum 0} constant-time=
  "Compare two strings without an early exit."
  [left right]
  (java.security.MessageDigest/isEqual (.getBytes (str left) "UTF-8")
                                        (.getBytes (str right) "UTF-8")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} loopback-host-header?
  "True when Host header `value` names a loopback host on `port`."
  [value port]
  (let [[host p] (host-authority value)]
    (boolean (and (contains? loopback-host-names host) (= (str port) p)))))

(defn ^{:stratum 1} allowed-origin?
  "True when `origin` is the request's own origin (the Host header's host
   and port; a Host without a port matches the scheme's default), or, on a
   loopback bind, a loopback origin on the bound port."
  [origin host-header {:keys [loopback? port]}]
  (let [[oh op] (origin-authority origin)
        [h p]   (host-authority host-header)]
    (boolean
     (and oh
          (or (and (= oh h) (or (= op p) (and (nil? p) (contains? #{"80" "443"} op))))
              (and loopback? port (contains? loopback-host-names oh) (= (str port) op)))))))

(defn ^{:stratum 1} ingest-token-valid?
  "True when `req` carries `token` in the ingest-token header. False when
   no token was issued."
  [req token]
  (boolean (and (seq token)
                (constant-time= token (get-in req [:headers ingest-token-header])))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} refusal
  "A 403 response when `req` must be refused before routing, else nil.
   `bind` is `{:loopback? bool :port n}` for the running listener; the Host
   check applies only on a loopback bind."
  [req bind]
  (let [{:strs [origin host content-type]} (:headers req)
        mutating? (contains? #{:post :put :patch :delete} (:request-method req))]
    (cond
      (and (:loopback? bind) (not (loopback-host-header? host (:port bind))))
      (forbidden "host-not-allowed")

      (and origin
           (or mutating? (contains? #{"/ws" "/ws/events"} (:uri req)))
           (not (allowed-origin? origin host bind)))
      (forbidden "cross-origin-request")

      (and mutating?
           (str/starts-with? (str/lower-case (str content-type)) "text/plain"))
      (forbidden "text-plain-body-not-accepted"))))
