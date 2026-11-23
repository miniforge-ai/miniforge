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
(ns ai.miniforge.web-dashboard.server.request-guard-test
  "A dashboard on 127.0.0.1 refuses other host names, other origins,
   text/plain posts, and event ingest without this start's token. These
   tests start a real listener on an ephemeral port and send raw HTTP, so
   the Host and Origin headers are exactly what a browser or a rebinding
   attacker would send. The dashboard's home (discovery file, watched
   events, archive) is a temporary directory; nothing touches the real
   ~/.miniforge."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.event-stream.interface :as events]
   [ai.miniforge.web-dashboard.server :as server]
   [ai.miniforge.web-dashboard.server.auth :as auth]
   [ai.miniforge.web-dashboard.server.request-guard :as guard]
   [ai.miniforge.web-dashboard.state.workflows :as workflows]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "dashboard-guard-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- ^{:stratum 0} status
  "Send `head-lines` (and `body`) to 127.0.0.1:`port` as one raw HTTP/1.1
   request and return the response status code."
  [port head-lines & [body]]
  (with-open [socket (java.net.Socket. "127.0.0.1" (int port))]
    (.setSoTimeout socket 5000)
    (let [payload (str (str/join "\r\n" head-lines) "\r\n"
                       (when body
                         (str "Content-Length: " (count (.getBytes (str body) "UTF-8")) "\r\n"))
                       "\r\n" body)
          out     (.getOutputStream socket)]
      (.write out (.getBytes payload "UTF-8"))
      (.flush out)
      (let [line (.readLine (java.io.BufferedReader.
                             (java.io.InputStreamReader. (.getInputStream socket) "UTF-8")))]
        (parse-long (second (str/split (str line) #" ")))))))

(defn- ^{:stratum 0} upgrade-lines
  "A WebSocket upgrade request for `uri` with `origin`."
  [uri host origin]
  [(str "GET " uri " HTTP/1.1")
   (str "Host: " host)
   "Upgrade: websocket"
   "Connection: Upgrade"
   "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="
   "Sec-WebSocket-Version: 13"
   (str "Origin: " origin)])

(defn- ^{:stratum 0} body-stream [content]
  (java.io.ByteArrayInputStream. (.getBytes (str content) "UTF-8")))

(defn- ^{:stratum 0} ingest-lines
  "A POST to /api/events/ingest with `content-type` and extra `headers`."
  [port content-type & headers]
  (into [(str "POST /api/events/ingest HTTP/1.1")
         (str "Host: localhost:" port)
         (str "Content-Type: " content-type)
         "Connection: close"]
        headers))

(defn- ^{:stratum 0} get-request
  "A GET of /api/events with Host header `host` over plain HTTP."
  [host & [headers]]
  {:request-method :get :uri "/api/events" :scheme :http
   :headers (merge {"host" host} headers)})

(deftest ^{:stratum 0} origin-is-compared-by-scheme-host-and-port-test
  (let [bind    {:loopback? false :port 7878 :host "dashboard.example" :trust-proxy? true}
        upgrade (fn [origin bind]
                  (guard/refusal {:request-method :get :uri "/ws" :scheme :http
                                  :headers {"host" "dashboard.example"
                                            "x-forwarded-proto" "https"
                                            "origin" origin}}
                                 bind))]
    (testing "behind a trusted TLS proxy the dashboard's origin is https on 443"
      (is (nil? (upgrade "https://dashboard.example" bind)))
      (is (nil? (upgrade "https://DASHBOARD.example:443" bind))))
    (testing "the same host on another scheme or port is another origin"
      (is (some? (upgrade "https://dashboard.example:80" bind)))
      (is (some? (upgrade "http://dashboard.example" bind)))
      (is (some? (upgrade "https://dashboard.example.evil.example" bind))))
    (testing "without a trusted proxy X-Forwarded-Proto is ignored"
      (let [untrusted (assoc bind :trust-proxy? false)]
        (is (some? (upgrade "https://dashboard.example" untrusted)))
        (is (nil? (upgrade "http://dashboard.example" untrusted)))))))

(deftest ^{:stratum 0} explicit-trust-proxy-false-wins-over-the-environment-test
  (is (false? (server/trust-proxy-setting false "true")))
  (is (true? (server/trust-proxy-setting true nil)))
  (is (true? (server/trust-proxy-setting nil "TRUE")))
  (is (false? (server/trust-proxy-setting nil nil)))
  (is (false? (server/trust-proxy-setting nil "yes"))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} with-dashboard
  "Start a dashboard on 127.0.0.1 with a temporary home and call
   `(f port home)`."
  [f]
  (let [home (temp-dir)]
    (with-redefs [config/miniforge-home     (constantly home)
                  workflows/events-dir-path (str home "/events")]
      (let [handle (binding [*out* (java.io.StringWriter.)]
                     (server/start-server! {:port 0
                                            :host "127.0.0.1"
                                            :auth {:users []}
                                            :event-stream (events/create-event-stream {:sinks []})}))]
        (try
          (f (:port handle) home)
          (finally
            (binding [*out* (java.io.StringWriter.)]
              (server/stop-server! handle))))))))

(deftest ^{:stratum 1} session-cookie-is-secure-on-a-public-bind-behind-tls-test
  (let [login (fn [public-bind? headers & [trust-proxy?]]
                (let [auth-state (assoc (auth/build-auth-state
                                         {:users [{:username "alice" :password "wonderland"}]})
                                        :public-bind? public-bind?
                                        :trust-proxy? (if (nil? trust-proxy?) true trust-proxy?))]
                  (get-in (auth/handle-login-submit
                           auth-state
                           {:uri "/login" :request-method :post
                            :headers (merge {"content-type" "application/x-www-form-urlencoded"}
                                            headers)
                            :body (body-stream "username=alice&password=wonderland")})
                          [:headers "Set-Cookie"])))]
    (testing "a public bind reached over TLS sets Secure"
      (is (str/includes? (login true {"x-forwarded-proto" "https"}) "; Secure")))
    (testing "X-Forwarded-Proto counts only from a trusted proxy"
      (is (not (str/includes? (login true {"x-forwarded-proto" "https"} false) "Secure"))))
    (testing "plain HTTP or a loopback bind does not"
      (is (not (str/includes? (login true {}) "Secure")))
      (is (not (str/includes? (login false {"x-forwarded-proto" "https"}) "Secure"))))))

(deftest ^{:stratum 1} configured-loopback-address-is-allowed-test
  (testing "a dashboard bound to 127.1.2.3 answers to that address"
    (let [bind {:loopback? true :port 7878 :host "127.1.2.3"}]
      (is (nil? (guard/refusal (get-request "127.1.2.3:7878") bind)))
      (is (nil? (guard/refusal (get-request "localhost:7878") bind)))
      (is (some? (guard/refusal (get-request "127.9.9.9:7878") bind)))
      (is (some? (guard/refusal (get-request "rebind.example:7878") bind)))))
  (testing "a dashboard bound to the expanded IPv6 loopback answers to it and to [::1]"
    (let [bind {:loopback? true :port 7878 :host "[0:0:0:0:0:0:0:1]"}]
      (is (nil? (guard/refusal (get-request "[0:0:0:0:0:0:0:1]:7878") bind)))
      (is (nil? (guard/refusal (get-request "[::1]:7878") bind))))))

(deftest ^{:stratum 1} omitted-host-port-means-80-test
  (testing "on a port-80 bind, Host: localhost with no port is the dashboard"
    (let [bind {:loopback? true :port 80 :host "127.0.0.1"}]
      (is (nil? (guard/refusal (get-request "localhost") bind)))
      (is (nil? (guard/refusal (get-request "localhost:80") bind)))
      (is (nil? (guard/refusal (assoc (get-request "localhost" {"origin" "http://localhost"})
                                      :request-method :post
                                      :headers {"host" "localhost" "origin" "http://localhost"
                                                "content-type" "application/json"})
                               bind)))))
  (testing "on another port it is not"
    (is (some? (guard/refusal (get-request "localhost")
                              {:loopback? true :port 7878 :host "127.0.0.1"})))))

(deftest ^{:stratum 1} loopback-bind-ignores-forwarded-proto-test
  (testing "with trust-proxy on a loopback listener, local http requests still pass"
    (let [bind {:loopback? true :port 7878 :host "127.0.0.1" :trust-proxy? true}]
      (is (nil? (guard/refusal (get-request "localhost:7878" {"x-forwarded-proto" "https"}) bind)))
      (is (nil? (guard/refusal {:request-method :post :uri "/api/events/ingest" :scheme :http
                                :headers {"host" "localhost:7878"
                                          "x-forwarded-proto" "https"
                                          "origin" "http://localhost:7878"
                                          "content-type" "application/json"}}
                               bind)))
      (is (some? (guard/refusal (get-request "attacker.example:7878" {"x-forwarded-proto" "https"})
                                bind))
          "the Host check still applies"))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} loopback-dashboard-answers-only-loopback-hosts-test
  (with-dashboard
    (fn [port _]
      (testing "a rebound name is refused"
        (is (= 403 (status port ["GET /api/events HTTP/1.1" (str "Host: attacker.example:" port)
                                 "Connection: close"]))))
      (testing "the right name on another port is refused"
        (is (= 403 (status port ["GET /api/events HTTP/1.1" "Host: localhost:1"
                                 "Connection: close"]))))
      (testing "localhost, 127.0.0.1 and [::1] on the bound port are served"
        (doseq [host ["localhost" "127.0.0.1" "[::1]"]]
          (is (= 200 (status port ["GET /api/events HTTP/1.1" (str "Host: " host ":" port)
                                   "Connection: close"]))
              host))))))

(deftest ^{:stratum 2} websockets-refuse-other-origins-test
  (with-dashboard
    (fn [port _]
      (let [host (str "localhost:" port)]
        (testing "another site's page cannot open the event stream"
          (is (= 403 (status port (upgrade-lines "/ws" host "https://evil.example"))))
          (is (= 403 (status port (upgrade-lines "/ws/events" host "https://evil.example")))))
        (testing "the dashboard's own page can"
          (is (= 101 (status port (upgrade-lines "/ws" host (str "http://" host))))))))))

(deftest ^{:stratum 2} event-ingest-needs-the-token-and-a-json-body-test
  (with-dashboard
    (fn [port home]
      (let [discovery (str home "/dashboard.port")
            token     (:ingest-token (json/parse-string (slurp discovery) true))
            event     (json/generate-string {:event/type "workflow/started"})
            token-hdr (str "X-Miniforge-Ingest-Token: " token)]
        (testing "the discovery file carries the token and only its owner can read it"
          (is (seq token))
          (when-not (str/starts-with? (str/lower-case (System/getProperty "os.name")) "windows")
            (is (= "rw-------"
                   (java.nio.file.attribute.PosixFilePermissions/toString
                    (java.nio.file.Files/getPosixFilePermissions
                     (.toPath (java.io.File. discovery))
                     (make-array java.nio.file.LinkOption 0)))))))
        (testing "a text/plain post is refused, token or not"
          (is (= 403 (status port (ingest-lines port "text/plain") event)))
          (is (= 403 (status port (ingest-lines port "text/plain" token-hdr) event))))
        (testing "a JSON post without the token is refused"
          (is (= 403 (status port (ingest-lines port "application/json") event))))
        (testing "a JSON post from another origin is refused, token or not"
          (is (= 403 (status port (ingest-lines port "application/json" token-hdr
                                                "Origin: https://evil.example")
                             event))))
        (testing "a JSON post with the token is accepted"
          (is (= 202 (status port (ingest-lines port "application/json" token-hdr) event))))))))
