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
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.llm.network-health :as nh]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]
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

(defn- ^{:stratum 0} probe-request
  "The request map `network-healthy?` sends for `url` under environment
   `env`."
  [url env]
  (let [captured (atom nil)]
    (nh/network-healthy? url {:env env
                              :http-client (fn [request]
                                             (reset! captured request)
                                             (delay {:status 200}))})
    @captured))

;------------------------------------------------------------------------------ Layer 1

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
    (with-redefs [impl/getenv-value (fn [k]
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

(deftest ^{:stratum 1} probe-goes-direct-without-proxy-env-test
  (testing "no proxy variables: the request is the plain probe"
    (let [request (probe-request anthropic-probe-url {})]
      (is (= anthropic-probe-url (:url request)))
      (is (not (contains? request :proxy-url))))))

;------------------------------------------------------------------------------ Layer 2

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
