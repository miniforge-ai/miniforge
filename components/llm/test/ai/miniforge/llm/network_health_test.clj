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
  "Tests for `ai.miniforge.llm.network-health` — the URL-driven
   connectivity probe primitive that PR-B will schedule alongside the
   progress monitor."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.network-health :as nh]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]))

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

(defn- throwing-http-client
  "Build an http-client stub that throws `ex` synchronously (mirroring
   http-kit's documented dual-mode behavior where connection failures
   can surface as a *thrown* exception rather than an `:error` map)."
  [ex]
  (fn [_request-map]
    (throw ex)))

(defn- throwing-on-deref-http-client
  "Build an http-client stub that returns a deref-able whose `@` throws.
   http-kit can also surface failures this way for callers that drop
   into Java-thread land."
  [ex]
  (fn [_request-map]
    (reify clojure.lang.IDeref
      (deref [_] (throw ex)))))

;------------------------------------------------------------------------------ Probe-endpoint resolution (lives in llm-client/backends)

(deftest probe-endpoint-for-test
  ;; Co-located with the rest of each backend's config in
  ;; `llm-client/backends` — comment review on PR #1051 moved the
  ;; lookup out of network-health so the probe primitive stays
  ;; URL-driven and backend-agnostic.
  (testing "known backends resolve to provider-specific URLs"
    (is (= "https://api.anthropic.com/" (impl/probe-endpoint-for :claude)))
    (is (= "https://api.openai.com/"    (impl/probe-endpoint-for :codex)))
    (is (= "https://api2.cursor.sh/"    (impl/probe-endpoint-for :cursor)))
    (is (= "http://localhost:11434/api/version"
           (impl/probe-endpoint-for :ollama)))
    (is (= "http://localhost:1234/v1/models"
           (impl/probe-endpoint-for :openai-compat)))
    (is (= "https://api.anthropic.com/" (impl/probe-endpoint-for :opencode))
        "OpenCode defaults to Anthropic — the typical provider routing"))

  (testing "unknown backend falls through to the generic connectivity URL"
    (is (= "https://1.1.1.1/" (impl/probe-endpoint-for :unrecognized-backend)))
    (is (= "https://1.1.1.1/" (impl/probe-endpoint-for nil))
        "nil backend key still resolves to the generic fallback")))

;------------------------------------------------------------------------------ network-healthy? (URL-driven)

(deftest network-healthy?-treats-any-http-response-as-up-test
  ;; Connectivity is about TCP/TLS reach + an HTTP exchange; any status
  ;; code proves the network completed a round trip. 4xx is the common
  ;; case for an unauthenticated HEAD on api.anthropic.com (returns 405
  ;; method-not-allowed), and 5xx still proves connectivity.
  (let [test-url "https://api.example.com/"]
    (testing "2xx response → healthy"
      (is (nh/network-healthy?
            test-url
            {:http-client (stub-http-client {:status 200 :body ""})})))

    (testing "4xx response → healthy (server reachable, just rejected the request)"
      (is (nh/network-healthy?
            test-url
            {:http-client (stub-http-client {:status 405 :body "Method Not Allowed"})}))
      (is (nh/network-healthy?
            test-url
            {:http-client (stub-http-client {:status 401 :body "Unauthorized"})})))

    (testing "5xx response → healthy (provider degraded but network is up)"
      (is (nh/network-healthy?
            test-url
            {:http-client (stub-http-client {:status 503 :body "Service Unavailable"})})))

    (testing "3xx redirect response → healthy"
      (is (nh/network-healthy?
            test-url
            {:http-client (stub-http-client {:status 301 :body ""
                                             :headers {"Location" "https://elsewhere"}})})))))

(deftest network-healthy?-treats-connection-failure-as-down-test
  (let [test-url "https://api.example.com/"]
    (testing ":error key (connection refused, DNS failure, etc.) → false"
      (is (false? (nh/network-healthy?
                    test-url
                    {:http-client (stub-http-client
                                    {:error (java.net.ConnectException. "Connection refused")})}))))

    (testing "unknown-host exception → false"
      (is (false? (nh/network-healthy?
                    test-url
                    {:http-client (stub-http-client
                                    {:error (java.net.UnknownHostException. "no such host")})}))))

    (testing "timeout exception → false"
      (is (false? (nh/network-healthy?
                    test-url
                    {:http-client (stub-http-client
                                    {:error (java.net.SocketTimeoutException. "timeout")})}))))

    (testing "response missing :status field → false"
      (is (false? (nh/network-healthy?
                    test-url
                    {:http-client (stub-http-client {:body "anomalous"})}))))

    (testing "non-map response → false"
      (is (false? (nh/network-healthy?
                    test-url
                    {:http-client (stub-http-client nil)})))
      (is (false? (nh/network-healthy?
                    test-url
                    {:http-client (stub-http-client "not-a-map")}))))))

(deftest network-healthy?-treats-thrown-exception-as-down-test
  ;; Regression for PR #1051 comment review: http-kit can surface
  ;; connection failures as a *thrown* exception rather than an
  ;; `:error` map. The probe must catch both shapes — otherwise it
  ;; would throw and the PR-B scheduler loop's `(try ... (catch ...))`
  ;; would have to absorb the failure shape network-health was
  ;; supposed to encapsulate.
  (let [test-url "https://api.example.com/"]
    (testing "synchronous exception from the http-client → false"
      (is (false? (nh/network-healthy?
                    test-url
                    {:http-client (throwing-http-client
                                    (java.net.ConnectException. "Connection refused"))}))))

    (testing "exception on @deref → false"
      (is (false? (nh/network-healthy?
                    test-url
                    {:http-client (throwing-on-deref-http-client
                                    (java.net.SocketTimeoutException. "timeout"))}))))

    (testing "RuntimeException from either path → false (defensive)"
      (is (false? (nh/network-healthy?
                    test-url
                    {:http-client (throwing-http-client
                                    (RuntimeException. "DNS resolver crash"))})))
      (is (false? (nh/network-healthy?
                    test-url
                    {:http-client (throwing-on-deref-http-client
                                    (RuntimeException. "wat"))}))))))

;------------------------------------------------------------------------------ Request shape

(deftest network-healthy?-sends-head-request-test
  (testing "probe uses HEAD method against the URL it was given"
    (let [capture (atom {})]
      (nh/network-healthy?
        "https://api.anthropic.com/"
        {:http-client (stub-http-client {:status 200} :capture capture)})
      (is (= :head (get-in @capture [:last-request :method])))
      (is (= "https://api.anthropic.com/"
             (get-in @capture [:last-request :url]))
          "URL is the one the caller passed in — no internal mapping"))))

(deftest network-healthy?-default-timeout-test
  (testing "default-probe-timeout-ms is propagated to the http-client"
    (let [capture (atom {})]
      (nh/network-healthy?
        "https://api.example.com/"
        {:http-client (stub-http-client {:status 200} :capture capture)})
      (is (= nh/default-probe-timeout-ms
             (get-in @capture [:last-request :timeout]))))))

(deftest network-healthy?-custom-timeout-test
  (testing ":timeout-ms in opts overrides the default"
    (let [capture (atom {})]
      (nh/network-healthy?
        "https://api.example.com/"
        {:timeout-ms  500
         :http-client (stub-http-client {:status 200} :capture capture)})
      (is (= 500 (get-in @capture [:last-request :timeout]))))))
