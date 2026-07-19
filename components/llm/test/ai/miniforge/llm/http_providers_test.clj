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
  "Env-var name that must not exist in any test environment — drives
   the missing-API-key error path."
  "MF_TEST_NONEXISTENT_API_KEY")

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
  (testing "explicit messages pass through with only wire keys"
    (let [body (impl/anthropic-request-body
                {:messages [{:role "user" :content "a" :extra "x"}
                            {:role "assistant" :content "b"}]
                 :model "m"})]
      (is (= [{:role "user" :content "a"}
              {:role "assistant" :content "b"}]
             (:messages body))))))

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

(deftest missing-api-key-test
  (let [result (impl/http-complete {:provider "Anthropic"
                                    :api-key-env missing-key-env
                                    :api-endpoint "http://llm-test.invalid/"}
                                   {:prompt "q" :model "m"}
                                   {})]
    (testing "no config key and no env var fails closed before any request"
      (is (not (:success result)))
      (is (= "missing_api_key" (get-in result [:error :type])))
      (is (= :invalid-input (get-in result [:anomaly :anomaly/type]))))))

(deftest provider-error-responses-test
  (testing "non-200 surfaces the provider's error message"
    (let [{:keys [result]}
          (capture-http {:status 429
                         :body (json/generate-string
                                {:error {:type "rate_limit_error"
                                         :message "slow down"}})}
                        #(impl/http-complete (backend-config :anthropic-api)
                                             {:prompt "q" :model "m"}
                                             {:api-key test-api-key}))]
      (is (not (:success result)))
      (is (= "api_error" (get-in result [:error :type])))
      (is (= "slow down" (get-in result [:error :message])))))
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

(deftest ollama-usage-nil-guard-test
  (testing "missing eval counts produce an empty usage map, not a nil-keyed one"
    (let [result (impl/parse-ollama-response
                  (http-200 {:message {:content "hi"}}))]
      (is (:success result))
      (is (= {} (:usage result)))
      (is (= 0 (:tokens result))))))

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
