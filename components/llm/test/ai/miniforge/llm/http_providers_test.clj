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

(ns ai.miniforge.llm.http-providers-test
  "Direct API-key HTTP provider backends (:anthropic-api / :openai-api /
   :gemini-api): request-body shaping, auth headers, endpoint
   resolution, response parsing, and API-key resolution. The transport
   (`http-post-request`) is stubbed via `with-redefs` — no test here
   touches the network."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures

(def ^:private test-api-key
  "Opaque key value asserted against captured request headers. Passed
   as explicit `:api-key` config so a developer's real provider env
   vars can never leak into (or satisfy) these tests."
  "mf-test-api-key")

(def ^:private missing-key-env
  "Env-var name driving the missing-API-key error path. Unique per test
   run (nanotime suffix), so the test never depends on a hard-coded name
   happening to be unset in the host environment."
  (str "MF_TEST_NONEXISTENT_API_KEY_" (System/nanoTime)))

(defn- http-200
  "HTTP response fixture with the given body map JSON-encoded."
  [body]
  {:status 200 :body (json/generate-string body)})

(defn- anthropic-200
  [text]
  (http-200 {:content [{:type "text" :text text}]
             :usage {:input_tokens 11 :output_tokens 7}}))

(defn- openai-200
  [text]
  (http-200 {:choices [{:message {:role "assistant" :content text}}]
             :usage {:prompt_tokens 11 :completion_tokens 7}}))

(defn- gemini-200
  [text]
  (http-200 {:candidates [{:content {:parts [{:text text}]}}]
             :usageMetadata {:promptTokenCount 11 :candidatesTokenCount 7}}))

(defn- capture-http
  "Run `f` with `http-post-request` stubbed to record its arguments
   and return `response`. Returns {:result <f's value> :captured
   {:url :headers :body}}."
  [response f]
  (let [captured (atom nil)]
    (with-redefs [impl/http-post-request
                  (fn [url headers body]
                    (reset! captured {:url url :headers headers :body body})
                    response)]
      {:result (f) :captured @captured})))

(defn- backend-config
  [backend]
  (get impl/backends backend))

;------------------------------------------------------------------------------ Layer 1
;; Request-body builders

(deftest anthropic-request-body-test
  (testing "prompt becomes a single user message; max_tokens defaults from config"
    (let [body (impl/anthropic-request-body {:prompt "hi" :model "claude-opus-4-8"})]
      (is (= "claude-opus-4-8" (:model body)))
      (is (= [{:role "user" :content "hi"}] (:messages body)))
      (is (pos-int? (:max_tokens body)))
      (is (not (contains? body :system)))))
  (testing "explicit max-tokens and system pass through"
    (let [body (impl/anthropic-request-body {:prompt "hi"
                                             :model "m"
                                             :system "be terse"
                                             :max-tokens 42})]
      (is (= 42 (:max_tokens body)))
      (is (= "be terse" (:system body)))))
  (testing "present-but-nil max-tokens falls back to the default — never
            \"max_tokens\": null on the wire (Anthropic requires it)"
    (let [body (impl/anthropic-request-body {:prompt "hi"
                                             :model "m"
                                             :max-tokens nil})]
      (is (pos-int? (:max_tokens body)))))
  (testing "explicit messages pass through with only wire keys"
    (let [body (impl/anthropic-request-body
                {:messages [{:role "user" :content "a" :extra "x"}
                            {:role "assistant" :content "b"}]
                 :model "m"})]
      (is (= [{:role "user" :content "a"}
              {:role "assistant" :content "b"}]
             (:messages body))))))

(deftest ollama-request-body-num-ctx-test
  (testing ":num-ctx becomes Ollama's options.num_ctx; absent means no options
            key at all (Ollama then applies its own default)"
    (let [body (impl/ollama-request-body {:prompt "hi" :model "m" :num-ctx 32768})]
      (is (= {:num_ctx 32768} (:options body))))
    (let [body (impl/ollama-request-body {:prompt "hi" :model "m"})]
      (is (not (contains? body :options))))))

(deftest ollama-num-ctx-threads-from-client-config-test
  (testing "a client created with :num-ctx sends it on every Ollama request —
            the silent-truncation guard for long prompts"
    (let [{:keys [captured]}
          (capture-http (http-200 {:message {:content "ok"}
                                   :prompt_eval_count 1 :eval_count 1})
                        (fn []
                          (llm/complete (llm/create-client {:backend :ollama
                                                            :model "m"
                                                            :num-ctx 65536})
                                        {:prompt "long surface"})))]
      (is (= {:num_ctx 65536} (:options (:body captured)))))))

(deftest openai-request-body-test
  (testing "system becomes the leading system-role message"
    (let [body (impl/openai-request-body {:prompt "hi" :model "gpt-x" :system "s"})]
      (is (= [{:role "system" :content "s"}
              {:role "user" :content "hi"}]
             (:messages body)))))
  (testing "max-tokens maps to max_completion_tokens and is absent by default"
    (is (= 9 (:max_completion_tokens
              (impl/openai-request-body {:prompt "p" :model "m" :max-tokens 9}))))
    (is (not (contains? (impl/openai-request-body {:prompt "p" :model "m"})
                        :max_completion_tokens)))))

(deftest gemini-request-body-test
  (testing "assistant role maps to model; system rides in systemInstruction"
    (let [body (impl/gemini-request-body
                {:messages [{:role "user" :content "q"}
                            {:role "assistant" :content "a"}]
                 :system "s"})]
      (is (= [{:role "user" :parts [{:text "q"}]}
              {:role "model" :parts [{:text "a"}]}]
             (:contents body)))
      (is (= {:parts [{:text "s"}]} (:systemInstruction body)))
      (is (not (contains? body :generationConfig)))))
  (testing "max-tokens maps into generationConfig"
    (is (= {:maxOutputTokens 5}
           (:generationConfig (impl/gemini-request-body {:prompt "p" :max-tokens 5}))))))

;------------------------------------------------------------------------------ Layer 2
;; http-complete: headers, endpoints, parsing, key resolution

(deftest anthropic-api-round-trip-test
  (let [{:keys [result captured]}
        (capture-http (anthropic-200 "answer")
                      #(impl/http-complete (backend-config :anthropic-api)
                                           {:prompt "q" :model "claude-opus-4-8"}
                                           {:api-key test-api-key}))]
    (testing "request goes to the Messages API with key + version headers"
      (is (= "https://api.anthropic.com/v1/messages" (:url captured)))
      (is (= test-api-key (get-in captured [:headers "x-api-key"])))
      (is (string? (get-in captured [:headers "anthropic-version"])))
      (is (= "claude-opus-4-8" (get-in captured [:body :model]))))
    (testing "response parses to canonical success"
      (is (:success result))
      (is (= "answer" (:content result)))
      (is (= {:input-tokens 11 :output-tokens 7} (:usage result)))
      (is (= 18 (:tokens result))))))

(deftest openai-api-round-trip-test
  (let [{:keys [result captured]}
        (capture-http (openai-200 "answer")
                      #(impl/http-complete (backend-config :openai-api)
                                           {:prompt "q" :model "gpt-x"}
                                           {:api-key test-api-key}))]
    (testing "request carries a Bearer authorization header"
      (is (= "https://api.openai.com/v1/chat/completions" (:url captured)))
      (is (= (str "Bearer " test-api-key)
             (get-in captured [:headers "Authorization"]))))
    (testing "response parses choices + prompt/completion usage"
      (is (:success result))
      (is (= "answer" (:content result)))
      (is (= {:input-tokens 11 :output-tokens 7} (:usage result))))))

(deftest gemini-api-round-trip-test
  (let [{:keys [result captured]}
        (capture-http (gemini-200 "answer")
                      #(impl/http-complete (backend-config :gemini-api)
                                           {:prompt "q" :model "gemini-x"}
                                           {:api-key test-api-key}))]
    (testing "model is embedded in the URL path, key in x-goog-api-key"
      (is (= "https://generativelanguage.googleapis.com/v1beta/models/gemini-x:generateContent"
             (:url captured)))
      (is (= test-api-key (get-in captured [:headers "x-goog-api-key"]))))
    (testing "response parses candidates + usageMetadata"
      (is (:success result))
      (is (= "answer" (:content result)))
      (is (= {:input-tokens 11 :output-tokens 7} (:usage result))))))

(deftest openai-compat-wiring-test
  (testing "the generic backend rides the OpenAI wire shape, is
            credential-free by default (no :api-key-env), and defaults to
            the LM Studio endpoint"
    (let [entry (backend-config :openai-compat)]
      (is (= "http" (:cmd entry)))
      (is (= "OpenAI-Compatible" (:provider entry)))
      (is (nil? (:api-key-env entry)) "no env var -> never fails closed on a key")
      (is (= "http://localhost:1234/v1/chat/completions" (:api-endpoint entry)))
      (is (= "MINIFORGE_OPENAI_COMPAT_BASE_URL" (:base-url-env entry)))
      (is (false? (:requires-cli? entry))))))

(deftest openai-compat-keyless-round-trip-test
  (testing "a keyless local server gets no Authorization header; the OpenAI
            body/parse shapes are reused"
    (let [{:keys [result captured]}
          (capture-http (openai-200 "local answer")
                        (fn []
                          (llm/complete (llm/create-client {:backend :openai-compat
                                                            :model "qwen3-30b-a3b"})
                                        {:prompt "hi"})))]
      (is (true? (:success result)))
      (is (= "local answer" (:content result)))
      (is (= "http://localhost:1234/v1/chat/completions" (:url captured)))
      (is (not (contains? (:headers captured) "Authorization")))
      (is (= "qwen3-30b-a3b" (:model (:body captured)))))))

(deftest openai-compat-key-and-base-url-override-test
  (testing "a supplied :api-key becomes a bearer header and :base-url wins
            over the default endpoint"
    (let [{:keys [captured]}
          (capture-http (openai-200 "ok")
                        (fn []
                          (llm/complete (llm/create-client
                                         {:backend :openai-compat
                                          :model "m"
                                          :api-key test-api-key
                                          :base-url "http://localhost:8000/v1/chat/completions"})
                                        {:prompt "hi"})))]
      (is (= (str "Bearer " test-api-key)
             (get (:headers captured) "Authorization")))
      (is (= "http://localhost:8000/v1/chat/completions" (:url captured))))))

(deftest base-url-override-is-scoped-to-declaring-backends-test
  (testing "a stray :base-url on a fixed-endpoint provider is ignored —
            only backends declaring :base-url-env support the override"
    (let [{:keys [captured]}
          (capture-http (openai-200 "ok")
                        (fn []
                          (llm/complete (llm/create-client
                                         {:backend :openai-api
                                          :model "m"
                                          :api-key test-api-key
                                          :base-url "http://localhost:9999/hijack"})
                                        {:prompt "hi"})))]
      (is (= "https://api.openai.com/v1/chat/completions" (:url captured))))))

(deftest openai-compat-requires-model-test
  (testing "no model on client or request fails closed before any request"
    (let [{:keys [result captured]}
          (capture-http (openai-200 "never")
                        (fn []
                          (llm/complete (llm/create-client {:backend :openai-compat})
                                        {:prompt "hi"})))]
      (is (false? (:success result)))
      (is (= "missing_model" (get-in result [:error :type])))
      (is (nil? captured) "failed closed before the transport"))))

(deftest missing-api-key-test
  (let [result (impl/http-complete {:provider "Anthropic"
                                    :api-key-env missing-key-env
                                    :api-endpoint "http://llm-test.invalid/"}
                                   {:prompt "q" :model "m"}
                                   {})]
    (testing "no config key and no env var fails closed before any request"
      (is (not (:success result)))
      (is (= "missing_api_key" (get-in result [:error :type])))
      (is (= :invalid-input (get-in result [:anomaly :anomaly/type])))))
  (testing "a blank config :api-key is absent, not present — it falls through
            to the env var and, with none set, still fails closed"
    (doseq [blank ["" "   "]]
      (let [result (impl/http-complete {:provider "Anthropic"
                                        :api-key-env missing-key-env
                                        :api-endpoint "http://llm-test.invalid/"}
                                       {:prompt "q" :model "m"}
                                       {:api-key blank})]
        (is (not (:success result)) (pr-str blank))
        (is (= "missing_api_key" (get-in result [:error :type])))))))

(defn- provider-error
  "Run an anthropic-api request against a canned non-200 response and
   return the parsed result."
  [status body]
  (:result (capture-http {:status status :body (json/generate-string body)}
                         #(impl/http-complete (backend-config :anthropic-api)
                                              {:prompt "q" :model "m"}
                                              {:api-key test-api-key}))))

(deftest provider-error-responses-test
  (testing "429 classifies as rate-limited, preserving status + provider message"
    (let [result (provider-error 429 {:error {:type "rate_limit_error"
                                              :message "slow down"}})]
      (is (not (:success result)))
      (is (= "api_error" (get-in result [:error :type])))
      (is (= "Provider returned HTTP 429: slow down"
             (get-in result [:error :message])))
      (is (= :anomalies.agent/rate-limited
             (get-in result [:anomaly :anomaly/subtype])))))
  (testing "403 classifies as forbidden — not a parse error, not generic"
    (let [result (provider-error 403 {:error {:message "key disabled"}})]
      (is (= :unauthorized (get-in result [:anomaly :anomaly/type])))
      (is (= "Provider returned HTTP 403: key disabled"
             (get-in result [:error :message])))))
  (testing "5xx classifies as unavailable"
    (let [result (provider-error 503 {:error {:message "overloaded"}})]
      (is (= :unavailable (get-in result [:anomaly :anomaly/type])))))
  (testing "a non-JSON body on a non-200 stays a status error, not a parse error"
    (let [{:keys [result]}
          (capture-http {:status 502 :body "<html>bad gateway</html>"}
                        #(impl/http-complete (backend-config :anthropic-api)
                                             {:prompt "q" :model "m"}
                                             {:api-key test-api-key}))]
      (is (= "api_error" (get-in result [:error :type])))
      (is (= :unavailable (get-in result [:anomaly :anomaly/type])))))
  (testing "a non-JSON body on a 200 returns a parse failure as data"
    (let [{:keys [result]}
          (capture-http {:status 200 :body "not-json"}
                        #(impl/http-complete (backend-config :anthropic-api)
                                             {:prompt "q" :model "m"}
                                             {:api-key test-api-key}))]
      (is (= "parse_error" (get-in result [:error :type])))
      (is (= :fault (get-in result [:anomaly :anomaly/type])))
      (is (= :parse-provider-response
             (get-in result [:anomaly :anomaly/data :operation])))))
  (testing "200 with no generated text fails instead of passing empty content"
    (let [{:keys [result]}
          (capture-http (http-200 {:content [] :usage {}})
                        #(impl/http-complete (backend-config :anthropic-api)
                                             {:prompt "q" :model "m"}
                                             {:api-key test-api-key}))]
      (is (not (:success result)))
      (is (= "empty_success_output" (get-in result [:error :type])))))
  (testing "unsupported provider is rejected"
    (let [result (impl/http-complete {:provider "Mystery"}
                                     {:prompt "q"}
                                     {})]
      (is (= "unsupported_backend" (get-in result [:error :type]))))))

(deftest missing-model-test
  (testing "an API provider without a model fails closed before any request"
    (let [result (impl/http-complete (backend-config :anthropic-api)
                                     {:prompt "q"}
                                     {:api-key test-api-key})]
      (is (not (:success result)))
      (is (= "missing_model" (get-in result [:error :type])))
      (is (= :invalid-input (get-in result [:anomaly :anomaly/type])))))
  (testing "ollama without a model still runs (body builder defaults it)"
    (let [{:keys [result captured]}
          (capture-http (http-200 {:message {:content "hi"}})
                        #(impl/http-complete (backend-config :ollama)
                                             {:prompt "q"}
                                             {}))]
      (is (:success result))
      (is (string? (get-in captured [:body :model]))))))

(deftest ollama-usage-nil-guard-test
  (testing "missing eval counts produce an empty usage map, not a nil-keyed one"
    (let [result (impl/parse-ollama-response
                  (http-200 {:message {:content "hi"}}))]
      (is (:success result))
      (is (= {} (:usage result)))
      (is (= 0 (:tokens result))))))

(deftest ollama-response-failures-are-data-test
  (testing "a non-OK response is classified by status without throwing"
    (let [result (impl/parse-ollama-response
                  {:status 503 :body (json/generate-string {:error "overloaded"})})]
      (is (= "api_error" (get-in result [:error :type])))
      (is (= :unavailable (get-in result [:anomaly :anomaly/type])))))
  (testing "malformed JSON on a 200 becomes a parse failure with provenance"
    (let [result (impl/parse-ollama-response {:status 200 :body "not-json"})]
      (is (= "parse_error" (get-in result [:error :type])))
      (is (= :fault (get-in result [:anomaly :anomaly/type])))
      (is (= :parse-provider-response
             (get-in result [:anomaly :anomaly/data :operation]))))))

;------------------------------------------------------------------------------ Layer 3
;; Client integration: config threading through complete / complete-stream

(deftest client-round-trip-test
  (testing "client :model and :api-key flow through complete"
    (let [{:keys [result captured]}
          (capture-http (anthropic-200 "done")
                        #(llm/complete (llm/create-client {:backend :anthropic-api
                                                           :model "claude-opus-4-8"
                                                           :api-key test-api-key})
                                       {:prompt "q"}))]
      (is (= "claude-opus-4-8" (get-in captured [:body :model])))
      (is (= test-api-key (get-in captured [:headers "x-api-key"])))
      (is (= "done" (:content result)))))
  (testing "complete-stream falls back to one terminal chunk"
    (let [chunks (atom [])
          {:keys [result]}
          (capture-http (anthropic-200 "streamed")
                        #(llm/complete-stream
                          (llm/create-client {:backend :anthropic-api
                                              :model "m"
                                              :api-key test-api-key})
                          {:prompt "q"}
                          (fn [chunk] (swap! chunks conj chunk))))]
      (is (= "streamed" (:content result)))
      (is (= [{:delta "streamed" :done? true :content "streamed"}]
             @chunks)))))
