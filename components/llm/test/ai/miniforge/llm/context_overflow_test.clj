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

(ns ai.miniforge.llm.context-overflow-test
  "Context-overflow is classified as a distinct terminal error type from the
   structured usage token counts (locale- and backend-independent), NOT from
   the backend's localized 'prompt is too long' text — so the submission-only
   retry can't fire on it."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]))

(defn- private-fn [sym]
  (var-get (ns-resolve 'ai.miniforge.llm.protocols.impl.llm-client sym)))

(deftest total-input-tokens-test
  (testing "sums prompt + cache-creation + cache-read; the real prompt size
            lives mostly in cache-creation, not :input-tokens"
    (is (= 204282 (impl/total-input-tokens
                   {:input-tokens 3
                    :cache-creation-input-tokens 204279
                    :cache-read-input-tokens 0})))
    (is (= 0 (impl/total-input-tokens nil)))
    (is (= 5 (impl/total-input-tokens {:input-tokens 5})))
    (is (= 0 (impl/total-input-tokens
              {:input-tokens nil
               :cache-creation-input-tokens nil
               :cache-read-input-tokens nil})))))

(deftest context-overflow-by-usage?-test
  (testing "true once total input tokens reach the model's context window"
    ;; the actual adhoc-2135293220 shape: 204,282 input vs a 200k window
    (is (impl/context-overflow-by-usage?
         {:input-tokens 3 :cache-creation-input-tokens 204279} 200000))
    (is (impl/context-overflow-by-usage? {:input-tokens 200000} 200000)))

  (testing "false below the window, with no window, or with no usage"
    (is (not (impl/context-overflow-by-usage? {:input-tokens 199999} 200000)))
    (is (not (impl/context-overflow-by-usage? {:input-tokens 204282} nil)))
    (is (not (impl/context-overflow-by-usage? {} 200000)))
    (is (not (impl/context-overflow-by-usage? nil 200000)))))

(deftest streaming-error-response-classifies-overflow-test
  (testing "input tokens >= window => terminal context_overflow type, not cli_error"
    (let [resp (impl/streaming-error-response
                "Prompt is too long" 0 nil "Prompt is too long"
                nil nil nil 0 nil
                {:input-tokens 3 :cache-creation-input-tokens 204279} 200000)]
      (is (= impl/context-overflow-error-type (get-in resp [:error :type])))
      (is (not= "cli_error" (get-in resp [:error :type])))))

  (testing "an ordinary error under the window stays cli_error — even if its
            text happens to mention being long (no string matching anymore)"
    (let [resp (impl/streaming-error-response
                "boom" 1 "boom" "boom" nil nil nil 0 nil
                {:input-tokens 100} 200000)]
      (is (= "cli_error" (get-in resp [:error :type])))))

  (testing "a timeout still wins as adaptive_timeout regardless of usage"
    (let [resp (impl/streaming-error-response
                "x" 0 nil "x"
                {:type :stream-idle :elapsed-ms 1} nil nil 0 nil
                {:input-tokens 3 :cache-creation-input-tokens 204279} 200000)]
      (is (= "adaptive_timeout" (get-in resp [:error :type])))))

  (testing "the 9-arg form (no usage/window) stays backward-compatible and
            skips overflow classification"
    (let [resp (impl/streaming-error-response
                "" -1 "process died" "" nil nil nil 5 nil)]
      (is (= "cli_error" (get-in resp [:error :type]))))))

(deftest parsed-usage-omits-absent-fields-test
  (testing "a cache-only usage frame produces NO nil :input-tokens key, so
            downstream `(get usage :input-tokens 0)` defaulting still works
            (would otherwise NPE in llm-success's :tokens sum)"
    (let [parsed ((private-fn 'parsed-usage)
                  {:cache_creation_input_tokens 204279})]
      (is (= {:cache-creation-input-tokens 204279} parsed))
      (is (not (contains? parsed :input-tokens)))
      (is (= 0 (get parsed :input-tokens 0)))
      (is (= 204279 (impl/total-input-tokens parsed)))))

  (testing "nil when no numeric token field is present"
    (is (nil? ((private-fn 'parsed-usage) {})))
    (is (nil? ((private-fn 'parsed-usage) {:input_tokens nil})))))
