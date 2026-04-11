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

(ns ai.miniforge.loop.repair-messages-test
  "Tests that repair result factory functions use localized messages."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.loop.repair :as repair]
   [ai.miniforge.loop.messages :as messages]))

;; ============================================================================
;; Result factory functions use localized messages
;; ============================================================================

(deftest make-max-attempts-result-uses-localized-message-test
  (testing "message comes from the message catalog, not a hardcoded string"
    (let [result (repair/make-max-attempts-result 3 [] [{:code :err}])
          expected (messages/t :repair/max-attempts-exceeded)]
      (is (= expected (:message result)))
      (is (false? (:success? result)))
      (is (= 3 (:attempts result))))))

(deftest make-exhausted-strategies-result-uses-localized-message-test
  (testing "message comes from the message catalog"
    (let [result (repair/make-exhausted-strategies-result 2 [] [{:code :err}])
          expected (messages/t :repair/strategies-exhausted)]
      (is (= expected (:message result)))
      (is (false? (:success? result))))))

(deftest make-escalation-result-uses-localized-message-test
  (testing "escalation message comes from the message catalog"
    (let [result (repair/make-escalation-result {:artifact "a"} 1 [] [{:code :err}])
          expected (messages/t :repair/escalated)]
      (is (= expected (:message result)))
      (is (true? (:escalate? result)))
      (is (false? (:success? result))))))

(deftest make-success-result-has-no-message-test
  (testing "success result does not include a message key"
    (let [result (repair/make-success-result {:artifact "a" :strategy :llm-fix} 0 [])]
      (is (true? (:success? result)))
      (is (nil? (:message result))))))
