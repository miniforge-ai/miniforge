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
(ns ai.miniforge.llm.network-probe-proxy-test
  "The network-drop monitor runs for streaming CLI calls, and its probe
   takes the proxy route the agent CLI takes (HTTPS_PROXY / HTTP_PROXY /
   NO_PROXY). A probe that ignored the proxy would fail on every attempt
   behind one, and the monitor would kill a healthy agent."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.llm.network-health :as nh]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]
   [ai.miniforge.llm.protocols.impl.llm-client.settings :as settings]
   [ai.miniforge.llm.protocols.records.llm-client :as records]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private anthropic-probe-url
  "The :claude backend's probe endpoint."
  "https://api.anthropic.com/")

(def ^{:stratum 0} ^:private openai-probe-url
  "A second https endpoint, used as the control in NO_PROXY cases."
  "https://api.openai.com/")

(def ^{:stratum 0} ^:private local-http-probe-url
  "A plain-http probe endpoint (the :ollama backend's)."
  "http://localhost:11434/api/version")

(def ^{:stratum 0} ^:private corp-proxy
  "The proxy the tests expect the probe to use."
  "http://proxy.corp.example:3128")

(def ^{:stratum 0} ^:private other-proxy
  "A proxy the tests expect the probe NOT to use."
  "http://other-proxy.example:8080")

(def ^{:stratum 0} ^:private monitor-env-var
  "The switch that turns the network-drop monitor off."
  "MINIFORGE_NETWORK_MONITOR")

(def ^{:stratum 0} ^:private stub-stream-timeout
  "What the stub stream-exec-fn reports, so handle-streaming takes its
   error branch and never falls back to running a real CLI."
  {:type :stream-idle
   :message "No stream output"
   :elapsed-ms 1})

(def ^{:stratum 0} ^:private connect-established-reply
  "What the fake proxy answers to every CONNECT."
  "HTTP/1.1 200 Connection established\r\n\r\n")

(def ^{:stratum 0} ^:private expected-connect-line
  "The CONNECT request line a probe of `anthropic-probe-url` must send:
   authority form with the port."
  "CONNECT api.anthropic.com:443 HTTP/1.1")

(def ^{:stratum 0} ^:private probe-count
  "Probes sent through the fake proxy; more than one, so connection
   reuse would show."
  2)

(defn- ^{:stratum 0} answer-connects!
  "Fake-proxy side of one client connection: record each request line as
   [connection-number line] in `seen`, skip its headers, and answer with
   `reply`, until the client closes."
  [^java.net.Socket socket connection-number seen reply]
  (try
    (with-open [socket socket]
      (let [in (io/reader (.getInputStream socket))
            out (.getOutputStream socket)]
        (loop []
          (when-let [request-line (.readLine ^java.io.BufferedReader in)]
            (doall (take-while seq (repeatedly #(.readLine ^java.io.BufferedReader in))))
            (swap! seen conj [connection-number request-line])
            (.write out (.getBytes ^String reply))
            (.flush out)
            (recur)))))
    (catch java.io.IOException _
      nil)))

(def ^{:stratum 0} ^:private gateway-url
  "A base URL the CLI is pointed at instead of the provider."
  "https://llm-gateway.corp.example/anthropic")

(def ^{:stratum 0} ^:private ignored-url
  "A base URL the active route must not pick."
  "https://ignored.example/")

(def ^{:stratum 0} ^:private failed-connect-status-lines
  "Answers a proxy gives to CONNECT when it cannot reach the provider."
  ["502 Bad Gateway" "503 Service Unavailable" "504 Gateway Timeout"])

(def ^{:stratum 0} ^:private auth-required-status-line
  "Answer to CONNECT from a proxy that wants credentials."
  "407 Proxy Authentication Required")

(defn- ^{:stratum 0} connect-reply
  "A bodiless proxy answer with `status-line`."
  [status-line]
  (str "HTTP/1.1 " status-line "\r\nContent-Length: 0\r\n\r\n"))

(defn- ^{:stratum 0} local-proxy-env
  "Environment that routes https probes through the proxy on `server`."
  [^java.net.ServerSocket server]
  {"HTTPS_PROXY" (str "http://127.0.0.1:" (.getLocalPort server))})

(defn- ^{:stratum 0} probe-endpoint-with-env
  "`probe-endpoint-for` `backend-key` when the environment is exactly
   `env`."
  [backend-key env]
  (with-redefs [settings/getenv-value (fn [k] (get env k))]
    (impl/probe-endpoint-for backend-key)))

(defn- ^{:stratum 0} probe-request
  "The request map `probe-network` sends for `url` under environment
   `env`."
  [url env]
  (let [captured (atom nil)]
    (nh/probe-network url {:env env
                              :http-client (fn [request]
                                             (reset! captured request)
                                             (delay {:status 200}))})
    @captured))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} serve-connects!
  "Run a fake proxy on `server` until it is closed: each accepted
   connection is answered by `answer-connects!`."
  [^java.net.ServerSocket server seen reply]
  (future
    (loop [connection-number 1]
      (let [socket (.accept server)]
        (future (answer-connects! socket connection-number seen reply))
        (recur (inc connection-number))))))

(defn- ^{:stratum 1} streaming-opts
  "The opts `handle-streaming` passes to stream-exec-fn for a :claude
   call while `monitor-env-var` holds `monitor-env-value` (nil = unset)."
  [monitor-env-value]
  (let [seen (atom nil)
        client (records/create-client
                {:backend :claude
                 :stream-exec-fn (fn [_cmd _on-line opts]
                                   (reset! seen opts)
                                   (impl/timeout-result [] stub-stream-timeout))})]
    (with-redefs [settings/getenv-value (fn [k]
                                          (if (= k monitor-env-var)
                                            monitor-env-value
                                            (System/getenv k)))]
      (llm/complete-stream client {:prompt "test"} (fn [_] nil)))
    @seen))

(deftest ^{:stratum 1} probe-uses-https-proxy-test
  (testing "with HTTPS_PROXY set, an https probe tunnels through it"
    (let [request (probe-request anthropic-probe-url {"HTTPS_PROXY" corp-proxy})]
      (is (= corp-proxy (:proxy-url request)))
      (is (true? (:tunnel? request)))
      (is (= -1 (:keepalive request)) "a tunnel is never reused for the next probe")
      (is (= "https://api.anthropic.com:443/" (:url request))
          "the URL names the port, so the CONNECT target carries it")))

  (testing "lowercase https_proxy wins over HTTPS_PROXY"
    (is (= corp-proxy
           (:proxy-url (probe-request anthropic-probe-url
                                      {"https_proxy" corp-proxy
                                       "HTTPS_PROXY" other-proxy})))))

  (testing "an https probe falls back to HTTP_PROXY"
    (is (= corp-proxy
           (:proxy-url (probe-request anthropic-probe-url {"HTTP_PROXY" corp-proxy})))))

  (testing "a proxy given without a scheme is an http proxy"
    (is (= corp-proxy
           (:proxy-url (probe-request anthropic-probe-url
                                      {"HTTPS_PROXY" "proxy.corp.example:3128"}))))))

(deftest ^{:stratum 1} probe-uses-http-proxy-for-http-url-test
  (testing "an http probe uses HTTP_PROXY, not HTTPS_PROXY, and no tunnel"
    (let [request (probe-request local-http-probe-url {"HTTP_PROXY" corp-proxy
                                                       "HTTPS_PROXY" other-proxy})]
      (is (= corp-proxy (:proxy-url request)))
      (is (false? (:tunnel? request))))))

(deftest ^{:stratum 1} probe-skips-proxy-for-no-proxy-hosts-test
  (testing "a host listed in NO_PROXY goes direct; other hosts still use the proxy"
    (let [env {"HTTPS_PROXY" corp-proxy
               "NO_PROXY" "localhost, api.anthropic.com"}]
      (let [request (probe-request anthropic-probe-url env)]
        (is (nil? (:proxy-url request)))
        (is (= anthropic-probe-url (:url request))))
      (is (= corp-proxy (:proxy-url (probe-request openai-probe-url env))))))

  (testing "a leading . or * exempts every host under the domain"
    (doseq [entry [".anthropic.com" "*.anthropic.com"]]
      (let [env {"HTTPS_PROXY" corp-proxy "NO_PROXY" entry}]
        (is (nil? (:proxy-url (probe-request anthropic-probe-url env))) entry)
        (is (= corp-proxy (:proxy-url (probe-request openai-probe-url env))) entry))))

  (testing "a bare domain matches only that exact host, as in Node"
    (is (= corp-proxy
           (:proxy-url (probe-request anthropic-probe-url {"HTTPS_PROXY" corp-proxy
                                                           "NO_PROXY" "anthropic.com"})))))

  (testing "* exempts every host"
    (is (nil? (:proxy-url (probe-request openai-probe-url {"HTTPS_PROXY" corp-proxy
                                                           "NO_PROXY" "*"})))))

  (testing "an entry with a port matches only that port"
    (is (= corp-proxy
           (:proxy-url (probe-request anthropic-probe-url
                                      {"HTTPS_PROXY" corp-proxy
                                       "NO_PROXY" "api.anthropic.com:8443"}))))
    (is (nil? (:proxy-url (probe-request anthropic-probe-url
                                         {"HTTPS_PROXY" corp-proxy
                                          "NO_PROXY" "api.anthropic.com:443"})))))

  (testing "lowercase no_proxy wins over NO_PROXY"
    (is (nil? (:proxy-url (probe-request anthropic-probe-url
                                         {"HTTPS_PROXY" corp-proxy
                                          "no_proxy" "api.anthropic.com"
                                          "NO_PROXY" "other.example"}))))))

(deftest ^{:stratum 1} probe-proxy-host-leaves-out-credentials-test
  (testing "the proxy host for diagnostics is host:port, never the credentials in the URL"
    (is (= "proxy.corp.example:3128"
           (nh/probe-proxy-host anthropic-probe-url
                                {"HTTPS_PROXY" "http://user:secret@proxy.corp.example:3128"}))))
  (testing "nil when the probe goes direct"
    (is (nil? (nh/probe-proxy-host anthropic-probe-url {})))))

(deftest ^{:stratum 1} probe-goes-direct-without-proxy-env-test
  (testing "no proxy variables: the request is the plain probe"
    (let [request (probe-request anthropic-probe-url {})]
      (is (= anthropic-probe-url (:url request)))
      (is (not (contains? request :proxy-url))))))

(deftest ^{:stratum 1} probe-endpoint-follows-cli-endpoint-settings-test
  (testing "ANTHROPIC_BASE_URL moves the :claude probe to that gateway"
    (is (= gateway-url (probe-endpoint-with-env :claude {"ANTHROPIC_BASE_URL" gateway-url}))))

  (testing "OPENAI_BASE_URL moves the :codex probe"
    (is (= gateway-url (probe-endpoint-with-env :codex {"OPENAI_BASE_URL" gateway-url}))))

  (testing "Bedrock: its own base URL, else the regional endpoint; ANTHROPIC_BASE_URL does not apply"
    (is (= gateway-url
           (probe-endpoint-with-env :claude {"CLAUDE_CODE_USE_BEDROCK" "1"
                                             "ANTHROPIC_BEDROCK_BASE_URL" gateway-url
                                             "ANTHROPIC_BASE_URL" ignored-url})))
    (is (= "https://bedrock-runtime.eu-west-1.amazonaws.com/"
           (probe-endpoint-with-env :claude {"CLAUDE_CODE_USE_BEDROCK" "1"
                                             "AWS_REGION" "eu-west-1"
                                             "ANTHROPIC_BASE_URL" ignored-url}))))

  (testing "Vertex: its own base URL, else the global or regional endpoint"
    (is (= gateway-url
           (probe-endpoint-with-env :claude {"CLAUDE_CODE_USE_VERTEX" "1"
                                             "ANTHROPIC_VERTEX_BASE_URL" gateway-url})))
    (is (= "https://aiplatform.googleapis.com/"
           (probe-endpoint-with-env :claude {"CLAUDE_CODE_USE_VERTEX" "1"
                                             "CLOUD_ML_REGION" "global"})))
    (is (= "https://us-east5-aiplatform.googleapis.com/"
           (probe-endpoint-with-env :claude {"CLAUDE_CODE_USE_VERTEX" "1"
                                             "CLOUD_ML_REGION" "us-east5"}))))

  (testing "a provider switch that reads as off does not apply"
    (is (= gateway-url
           (probe-endpoint-with-env :claude {"CLAUDE_CODE_USE_BEDROCK" "0"
                                             "AWS_REGION" "eu-west-1"
                                             "ANTHROPIC_BASE_URL" gateway-url}))))

  (testing "the static endpoint when the route yields nothing usable"
    (is (= anthropic-probe-url
           (probe-endpoint-with-env :claude {"CLAUDE_CODE_USE_BEDROCK" "1"}))
        "Bedrock on, no base URL or region")
    (is (= anthropic-probe-url
           (probe-endpoint-with-env :claude {"ANTHROPIC_BASE_URL" "gateway.corp.example:8080"}))
        "not an http(s) URL")
    (is (= anthropic-probe-url (probe-endpoint-with-env :claude {}))
        "nothing set")))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} probe-through-local-proxy-test
  (testing "each probe opens its own CONNECT tunnel to host:443 through the proxy"
    (with-open [server (java.net.ServerSocket. 0)]
      (let [seen (atom [])
            env {"HTTPS_PROXY" (str "http://127.0.0.1:" (.getLocalPort server))}]
        (serve-connects! server seen connect-established-reply)
        (dotimes [_ probe-count]
          (is (= :healthy (nh/probe-network anthropic-probe-url {:env env}))))
        (is (= (mapv (fn [n] [n expected-connect-line]) (range 1 (inc probe-count)))
               @seen)
            "one connection per probe, each a CONNECT to api.anthropic.com:443")))))

(deftest ^{:stratum 2} probe-through-proxy-that-cannot-reach-provider-test
  (testing "a CONNECT answered 502/503/504 is a failed probe"
    (doseq [status-line failed-connect-status-lines]
      (with-open [server (java.net.ServerSocket. 0)]
        (serve-connects! server (atom []) (connect-reply status-line))
        (is (= :unreachable (nh/probe-network anthropic-probe-url {:env (local-proxy-env server)}))
            status-line))))

  (testing "a CONNECT answered 407 is reported as proxy-auth-required, not healthy"
    (with-open [server (java.net.ServerSocket. 0)]
      (serve-connects! server (atom []) (connect-reply auth-required-status-line))
      (is (= :proxy-auth-required
             (nh/probe-network anthropic-probe-url {:env (local-proxy-env server)}))))))

(deftest ^{:stratum 2} handle-streaming-starts-network-monitor-test
  (testing "a streaming CLI call passes :backend-key, which starts the network-drop monitor"
    (is (= :claude (:backend-key (streaming-opts nil)))))

  (testing "MINIFORGE_NETWORK_MONITOR=off (or false/0/no, any case) keeps it off"
    (doseq [value ["off" "OFF" " false " "0" "no"]]
      (let [opts (streaming-opts value)]
        (is (some? opts) "the stub ran")
        (is (not (contains? opts :backend-key)) value))))

  (testing "any other value leaves it on"
    (is (= :claude (:backend-key (streaming-opts "on"))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.llm.network-probe-proxy-test)
  :leave-this-here)
