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
     (localhost, 127.0.0.1, [::1], or the configured bind address) on its
     own port;
   - a WebSocket upgrade and every state-changing request with an Origin
     header must come from the dashboard's own origin (or, on a loopback
     bind, a loopback origin on its port);
   - a state-changing request with a text/plain body is refused outright:
     no dashboard client sends one, and it is the body a cross-site page
     can send without a preflight;
   - event ingest (/api/events/ingest, /ws/events) needs this start's
     token in the `X-Miniforge-Ingest-Token` header; the token is in the
     discovery file, readable only by the user who started the dashboard.

   Every comparison is between origins normalized by `normalize-origin`
   (java.net.URI): scheme, lower-cased host, and the effective port, 80
   for http and 443 for https when none is given."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} loopback-host-names
  "Host names a loopback-bound dashboard answers to, besides its
   configured bind address."
  ["localhost" "127.0.0.1" "[::1]"])

(def ^{:stratum 0} ingest-token-header
  "Request header (lower-cased, as the server presents headers) that
   carries the ingest token."
  "x-miniforge-ingest-token")

(defn ^{:stratum 0} normalize-origin
  "`scheme://host:port` for an http or https origin string (`scheme://host`
   with an optional port and nothing else), with the scheme and host
   lower-cased and the port made explicit: 80 for http, 443 for https.
   Nil for `null`, another scheme, a path, user info, or anything
   java.net.URI cannot parse as a server authority."
  [origin]
  (try
    (let [uri    (.parseServerAuthority (java.net.URI. (str origin)))
          scheme (some-> (.getScheme uri) str/lower-case)
          host   (some-> (.getHost uri) str/lower-case)
          port   (.getPort uri)]
      (when (and host
                 (contains? #{"http" "https"} scheme)
                 (nil? (.getRawUserInfo uri))
                 (contains? #{nil ""} (.getRawPath uri))
                 (nil? (.getRawQuery uri))
                 (nil? (.getRawFragment uri)))
        (str scheme "://" host ":"
             (if (neg? port) (if (= "https" scheme) 443 80) port))))
    (catch Exception _ nil)))

(defn ^{:stratum 0} external-scheme
  "The scheme the client used: X-Forwarded-Proto when the dashboard trusts
   a TLS proxy (`trust-proxy?`), else the connection's own scheme."
  [req trust-proxy?]
  (let [forwarded (some-> (get-in req [:headers "x-forwarded-proto"]) str/trim str/lower-case)]
    (if (and trust-proxy? (contains? #{"http" "https"} forwarded))
      forwarded
      (name (or (:scheme req) :http)))))

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

(defn ^{:stratum 1} loopback-origins
  "The origins a loopback-bound dashboard answers as: http on its port for
   localhost, 127.0.0.1, [::1] and its configured bind address `:host`."
  [{:keys [host port]}]
  (into #{}
        (keep #(normalize-origin (str "http://" % ":" port)))
        (cond-> loopback-host-names host (conj host))))

(defn ^{:stratum 1} request-origin
  "The normalized origin the request was addressed to: its external scheme
   and its Host header."
  [req {:keys [trust-proxy?]}]
  (normalize-origin (str (external-scheme req trust-proxy?) "://"
                         (get-in req [:headers "host"]))))

(defn ^{:stratum 1} ingest-token-valid?
  "True when `req` carries `token` in the ingest-token header. False when
   no token was issued."
  [req token]
  (boolean (and (seq token)
                (constant-time= token (get-in req [:headers ingest-token-header])))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} refusal
  "A 403 response when `req` must be refused before routing, else nil.
   `bind` describes the listener: `:loopback?`, `:port`, `:host` (the
   configured address, IPv6 in brackets) and `:trust-proxy?`."
  [req bind]
  (let [{:strs [origin content-type]} (:headers req)
        mutating? (contains? #{:post :put :patch :delete} (:request-method req))
        loopback  (when (:loopback? bind) (loopback-origins bind))
        addressed (request-origin req bind)]
    (cond
      (and loopback (not (contains? loopback addressed)))
      (forbidden "host-not-allowed")

      (and origin
           (or mutating? (contains? #{"/ws" "/ws/events"} (:uri req)))
           (let [o (normalize-origin origin)]
             (not (and o (or (= o addressed) (contains? loopback o))))))
      (forbidden "cross-origin-request")

      (and mutating?
           (str/starts-with? (str/lower-case (str content-type)) "text/plain"))
      (forbidden "text-plain-body-not-accepted"))))
