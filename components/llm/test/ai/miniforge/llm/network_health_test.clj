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

(ns ai.miniforge.llm.network-health-test
  "Tests for `ai.miniforge.llm.network-health` — the connectivity probe
   primitive that PR-B will schedule alongside the progress monitor."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.network-health :as nh]))

;------------------------------------------------------------------------------ Factories

(defn- stub-http-client
  "Build an http-client stub matching `org.httpkit.client/request`'s
   shape (called with a request-map, returns a deref-able). The stub
   `response` is what the caller's `@(http-client ...)` returns.

   When `:capture` is a non-nil atom, the request map is `swap!`'d into
   it as `:last-request` so a test can assert what was sent."
  [response & {:keys [capture]}]
  (fn [request-map]
    (when capture
      (swap! capture assoc :last-request request-map))
    (delay response)))

;------------------------------------------------------------------------------ endpoint-for

(deftest endpoint-for-test
  (testing "known backends resolve to provider-specific URLs"
    (is (= "https://api.anthropic.com/" (nh/endpoint-for :claude)))
    (is (= "https://api.openai.com/"     (nh/endpoint-for :codex)))
    (is (= "https://api2.cursor.sh/"     (nh/endpoint-for :cursor)))
    (is (= "http://localhost:11434/api/version" (nh/endpoint-for :ollama)))
    (is (= "https://api.anthropic.com/" (nh/endpoint-for :opencode))
        "OpenCode defaults to Anthropic — the typical provider routing"))

  (testing "unknown backend falls through to the generic connectivity URL"
    (is (= nh/generic-connectivity-probe-url
           (nh/endpoint-for :unrecognized-backend)))
    (is (= nh/generic-connectivity-probe-url
           (nh/endpoint-for nil))
        "nil backend key still resolves to the generic fallback")))

;------------------------------------------------------------------------------ network-healthy?

(deftest network-healthy?-treats-any-http-response-as-up-test
  ;; Connectivity is about TCP/TLS reach + an HTTP exchange; any status
  ;; code proves the network completed a round trip. 4xx is the common
  ;; case for an unauthenticated HEAD on api.anthropic.com (returns 405
  ;; method-not-allowed), and 5xx still proves connectivity — only an
  ;; absent response means the network is gone.
  (testing "2xx response → healthy"
    (is (nh/network-healthy?
          :claude
          {:http-client (stub-http-client {:status 200 :body ""})})))

  (testing "4xx response → healthy (server reachable, just rejected the request)"
    (is (nh/network-healthy?
          :claude
          {:http-client (stub-http-client {:status 405 :body "Method Not Allowed"})}))
    (is (nh/network-healthy?
          :claude
          {:http-client (stub-http-client {:status 401 :body "Unauthorized"})})))

  (testing "5xx response → healthy (provider degraded but network is up)"
    (is (nh/network-healthy?
          :claude
          {:http-client (stub-http-client {:status 503 :body "Service Unavailable"})})))

  (testing "3xx redirect response → healthy"
    (is (nh/network-healthy?
          :claude
          {:http-client (stub-http-client {:status 301 :body ""
                                           :headers {"Location" "https://elsewhere"}})}))))

(deftest network-healthy?-treats-connection-failure-as-down-test
  (testing ":error key (connection refused, DNS failure, etc.) → false"
    (is (false? (nh/network-healthy?
                  :claude
                  {:http-client (stub-http-client
                                  {:error (java.net.ConnectException. "Connection refused")})}))))

  (testing "unknown-host exception → false"
    (is (false? (nh/network-healthy?
                  :claude
                  {:http-client (stub-http-client
                                  {:error (java.net.UnknownHostException. "no such host")})}))))

  (testing "timeout exception → false"
    (is (false? (nh/network-healthy?
                  :claude
                  {:http-client (stub-http-client
                                  {:error (java.net.SocketTimeoutException. "timeout")})}))))

  (testing "response missing :status field → false"
    (is (false? (nh/network-healthy?
                  :claude
                  {:http-client (stub-http-client {:body "anomalous"})}))))

  (testing "non-map response → false"
    (is (false? (nh/network-healthy?
                  :claude
                  {:http-client (stub-http-client nil)})))
    (is (false? (nh/network-healthy?
                  :claude
                  {:http-client (stub-http-client "not-a-map")})))))

;------------------------------------------------------------------------------ Request shape

(deftest network-healthy?-sends-head-request-test
  (testing "probe uses HEAD method against the resolved endpoint"
    (let [capture (atom {})]
      (nh/network-healthy?
        :claude
        {:http-client (stub-http-client {:status 200} :capture capture)})
      (is (= :head (get-in @capture [:last-request :method])))
      (is (= "https://api.anthropic.com/"
             (get-in @capture [:last-request :url]))
          "request URL matches the provider-specific endpoint"))))

(deftest network-healthy?-default-timeout-test
  (testing "default-probe-timeout-ms is propagated to the http-client"
    (let [capture (atom {})]
      (nh/network-healthy?
        :claude
        {:http-client (stub-http-client {:status 200} :capture capture)})
      (is (= nh/default-probe-timeout-ms
             (get-in @capture [:last-request :timeout]))))))

(deftest network-healthy?-custom-timeout-test
  (testing ":timeout-ms in opts overrides the default"
    (let [capture (atom {})]
      (nh/network-healthy?
        :claude
        {:timeout-ms  500
         :http-client (stub-http-client {:status 200} :capture capture)})
      (is (= 500 (get-in @capture [:last-request :timeout]))))))

(deftest network-healthy?-unknown-backend-probes-fallback-test
  (testing "unknown backend probes the generic connectivity URL"
    (let [capture (atom {})]
      (nh/network-healthy?
        :brand-new-backend
        {:http-client (stub-http-client {:status 200} :capture capture)})
      (is (= nh/generic-connectivity-probe-url
             (get-in @capture [:last-request :url]))))))
