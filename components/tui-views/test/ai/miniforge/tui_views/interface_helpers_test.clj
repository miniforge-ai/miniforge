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

(ns ai.miniforge.tui-views.interface-helpers-test
  "Tests for extracted helper functions in interface.clj:
   action parsing, risk line parsing, chat message conversion."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-views.interface :as iface]))

;; ---------------------------------------------------------------------------- parse-risk-line

(deftest parse-risk-line-test
  (testing "parses valid RISK line"
    (let [r (iface/parse-risk-line "RISK: owner/repo#42 | high | Large change with failing CI")]
      (is (= ["owner/repo" 42] (:id r)))
      (is (= "high" (:level r)))
      (is (= "Large change with failing CI" (:reason r)))))

  (testing "returns nil for non-RISK lines"
    (is (nil? (iface/parse-risk-line "Some other text")))
    (is (nil? (iface/parse-risk-line "")))
    (is (nil? (iface/parse-risk-line "RISK: malformed")))))

;; ---------------------------------------------------------------------------- parse-risk-triage-response

(deftest parse-risk-triage-response-test
  (testing "parses multi-line response"
    (let [content "RISK: a/b#1 | low | Small\nSome noise\nRISK: c/d#2 | high | Big"
          result (iface/parse-risk-triage-response content)]
      (is (= 2 (count result)))
      (is (= ["a/b" 1] (:id (first result))))
      (is (= ["c/d" 2] (:id (second result))))))

  (testing "returns empty vector for no matches"
    (is (empty? (iface/parse-risk-triage-response "no risk lines here")))))

;; ---------------------------------------------------------------------------- action-match->action

(deftest action-match->action-test
  (let [match ["[ACTION: review | Review policy | Run packs]" "review" " Review policy " " Run packs "]
        result (iface/action-match->action match)]
    (is (= :review (:action result)))
    (is (= "Review policy" (:label result)))
    (is (= "Run packs" (:description result)))))

;; ---------------------------------------------------------------------------- parse-actions

(deftest parse-actions-test
  (testing "extracts actions from LLM text"
    (let [text "Some analysis.\n[ACTION: review | Check | Run review]\nMore text."
          [clean actions] (iface/parse-actions text)]
      (is (= 1 (count actions)))
      (is (= :review (:action (first actions))))
      (is (not (re-find #"\[ACTION:" clean)))))

  (testing "handles no actions"
    (let [[clean actions] (iface/parse-actions "Just plain text")]
      (is (= "Just plain text" clean))
      (is (empty? actions)))))

;; ---------------------------------------------------------------------------- chat-msg->llm-msg

(deftest chat-msg->llm-msg-test
  (let [result (iface/chat-msg->llm-msg {:role :user :content "hello"})]
    (is (= "user" (:role result)))
    (is (= "hello" (:content result)))))

;; ---------------------------------------------------------------------------- format-pr-summary-line

(deftest format-pr-summary-line-test
  (let [pr {:pr/repo "acme/app" :pr/number 42 :pr/title "Fix bug"}
        result (iface/format-pr-summary-line pr)]
    (is (= "- acme/app#42 Fix bug" result))))
