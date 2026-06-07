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
  "Context-overflow ('Prompt is too long') is classified as a distinct
   terminal error type so the submission-only retry can't fire on it."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]))

(deftest context-overflow-error?-test
  (testing "matches the CLI's overflow message case-insensitively"
    (is (impl/context-overflow-error? "Prompt is too long"))
    (is (impl/context-overflow-error? "prompt is too long"))
    (is (impl/context-overflow-error? "Error: PROMPT IS TOO LONG")))

  (testing "does not match ordinary errors or nil"
    (is (not (impl/context-overflow-error? "Stagnation timeout: no progress")))
    (is (not (impl/context-overflow-error? "")))
    (is (not (impl/context-overflow-error? nil)))))

(deftest streaming-error-response-classifies-overflow-test
  (testing "an overflow message gets the terminal context_overflow type, not cli_error"
    (let [resp (impl/streaming-error-response
                "Prompt is too long" 0 nil "Prompt is too long"
                nil nil nil 0 nil)]
      (is (= impl/context-overflow-error-type (get-in resp [:error :type])))
      (is (not= "cli_error" (get-in resp [:error :type])))))

  (testing "an ordinary CLI error stays cli_error"
    (let [resp (impl/streaming-error-response
                "boom" 1 "boom" "boom" nil nil nil 0 nil)]
      (is (= "cli_error" (get-in resp [:error :type])))))

  (testing "a timeout still wins as adaptive_timeout"
    (let [resp (impl/streaming-error-response
                "Prompt is too long" 0 nil "Prompt is too long"
                {:type :stream-idle :elapsed-ms 1} nil nil 0 nil)]
      (is (= "adaptive_timeout" (get-in resp [:error :type]))))))
