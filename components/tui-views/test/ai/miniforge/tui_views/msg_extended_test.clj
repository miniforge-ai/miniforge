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

(ns ai.miniforge.tui-views.msg-extended-test
  "Extended tests for msg.clj covering constructors missed by msg_test.clj:
   - prs-synced 2-arity (with error)
   - prs-synced-with-cache
   - fleet-risk-triaged / fleet-risk-triaged-error
   - workflows-archived
   - workflow-detail-loaded
   - pr-diff-fetched (error vs no-error)
   - phase-done with extras
   - workflow-done with extras"
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-views.msg :as msg]))

;; ---------------------------------------------------------------------------- Helpers

(defn msg-type [m] (first m))
(defn msg-payload [m] (second m))

;; ---------------------------------------------------------------------------- prs-synced arity variants

(deftest prs-synced-with-error-test
  (testing "2-arity includes error in payload"
    (let [m (msg/prs-synced [{:id 1}] "network timeout")]
      (is (= :msg/prs-synced (msg-type m)))
      (is (= [{:id 1}] (:pr-items (msg-payload m))))
      (is (= "network timeout" (:error (msg-payload m))))))

  (testing "2-arity with nil error omits :error key"
    (let [m (msg/prs-synced [{:id 1}] nil)]
      (is (not (contains? (msg-payload m) :error))))))

(deftest prs-synced-empty-items-test
  (testing "empty pr-items list is valid"
    (let [m (msg/prs-synced [])]
      (is (= :msg/prs-synced (msg-type m)))
      (is (= [] (:pr-items (msg-payload m)))))))

;; ---------------------------------------------------------------------------- prs-synced-with-cache

(deftest prs-synced-with-cache-test
  (testing "includes cached-risk in payload"
    (let [m (msg/prs-synced-with-cache [{:id 1}] {:risk-data true} nil)]
      (is (= :msg/prs-synced (msg-type m)))
      (is (= [{:id 1}] (:pr-items (msg-payload m))))
      (is (= {:risk-data true} (:cached-risk (msg-payload m))))
      (is (not (contains? (msg-payload m) :error)))))

  (testing "includes error when present"
    (let [m (msg/prs-synced-with-cache [] nil "fail")]
      (is (= "fail" (:error (msg-payload m))))))

  (testing "nil cached-risk is stored as nil"
    (let [p (msg-payload (msg/prs-synced-with-cache [] nil nil))]
      (is (nil? (:cached-risk p))))))

;; ---------------------------------------------------------------------------- fleet-risk-triaged

(deftest fleet-risk-triaged-test
  (testing "wraps assessments"
    (let [assessments [{:id ["r" 1] :level "high" :reason "Big"}]
          m (msg/fleet-risk-triaged assessments)]
      (is (= :msg/fleet-risk-triaged (msg-type m)))
      (is (= assessments (:assessments (msg-payload m))))
      (is (not (contains? (msg-payload m) :error))))))

(deftest fleet-risk-triaged-error-test
  (testing "wraps error string"
    (let [m (msg/fleet-risk-triaged-error "LLM timeout")]
      (is (= :msg/fleet-risk-triaged (msg-type m)))
      (is (= "LLM timeout" (:error (msg-payload m)))))))

;; ---------------------------------------------------------------------------- workflows-archived

(deftest workflows-archived-test
  (let [result {:archived 3 :failed 0}
        m (msg/workflows-archived result)]
    (is (= :msg/workflows-archived (msg-type m)))
    (is (= result (msg-payload m)))))

;; ---------------------------------------------------------------------------- workflow-detail-loaded

(deftest workflow-detail-loaded-test
  (let [wf-id (random-uuid)
        detail {:phases [{:name :plan}]}
        m (msg/workflow-detail-loaded wf-id detail)]
    (is (= :msg/workflow-detail-loaded (msg-type m)))
    (is (= wf-id (:workflow-id (msg-payload m))))
    (is (= detail (:detail (msg-payload m))))))

;; ---------------------------------------------------------------------------- pr-diff-fetched

(deftest pr-diff-fetched-test
  (testing "without error omits :error key"
    (let [m (msg/pr-diff-fetched ["r" 1] "diff" {:title "T"} nil)]
      (is (= :msg/pr-diff-fetched (msg-type m)))
      (is (= ["r" 1] (:pr-id (msg-payload m))))
      (is (= "diff" (:diff (msg-payload m))))
      (is (= {:title "T"} (:detail (msg-payload m))))
      (is (not (contains? (msg-payload m) :error)))))

  (testing "with error includes :error key"
    (let [m (msg/pr-diff-fetched ["r" 1] nil nil "gh not found")]
      (is (= "gh not found" (:error (msg-payload m))))
      (is (nil? (:diff (msg-payload m))))))

  (testing "nil diff and detail but no error"
    (let [p (msg-payload (msg/pr-diff-fetched ["r" 1] nil nil nil))]
      (is (nil? (:diff p)))
      (is (nil? (:detail p)))
      (is (not (contains? p :error))))))

;; ---------------------------------------------------------------------------- phase-done with extras

(deftest phase-done-with-extras-test
  (testing "extras are merged into payload"
    (let [m (msg/phase-done :wf1 :plan :success {:duration-ms 500})]
      (is (= :msg/phase-done (msg-type m)))
      (is (= :success (:outcome (msg-payload m))))
      (is (= 500 (:duration-ms (msg-payload m))))))

  (testing "without extras works normally"
    (let [m (msg/phase-done :wf1 :plan :failure)]
      (is (= :failure (:outcome (msg-payload m)))))))

;; ---------------------------------------------------------------------------- workflow-done with extras

(deftest workflow-done-with-extras-test
  (testing "extras are merged into payload"
    (let [m (msg/workflow-done :wf1 :success {:pr-info {:number 42}})]
      (is (= :msg/workflow-done (msg-type m)))
      (is (= :success (:status (msg-payload m))))
      (is (= {:number 42} (:pr-info (msg-payload m))))))

  (testing "without extras works normally"
    (let [m (msg/workflow-done :wf1 :failure)]
      (is (= :failure (:status (msg-payload m)))))))

;; ---------------------------------------------------------------------------- All messages are 2-element vectors

(deftest all-messages-are-vectors-test
  (testing "every constructor returns [keyword map-or-value]"
    (let [messages [(msg/prs-synced [])
                    (msg/prs-synced [] "err")
                    (msg/prs-synced-with-cache [] nil nil)
                    (msg/repos-discovered {})
                    (msg/repos-browsed {})
                    (msg/policy-evaluated :id {})
                    (msg/train-created :id "name")
                    (msg/prs-added-to-train {} 0)
                    (msg/merge-started 1 {})
                    (msg/review-completed [])
                    (msg/remediation-completed 0 0 "msg")
                    (msg/decomposition-started :id {})
                    (msg/chat-response "" [])
                    (msg/chat-action-result {})
                    (msg/fleet-risk-triaged [])
                    (msg/fleet-risk-triaged-error "e")
                    (msg/side-effect-error {})
                    (msg/workflows-archived {})
                    (msg/workflow-detail-loaded :id {})
                    (msg/pr-diff-fetched :id nil nil nil)
                    (msg/workflow-added :w "n" {})
                    (msg/phase-changed :w :p)
                    (msg/phase-done :w :p :o)
                    (msg/agent-status :w :a :s "m")
                    (msg/agent-output :w :a "d" false)
                    (msg/agent-started :w :a {})
                    (msg/agent-completed :w :a {})
                    (msg/agent-failed :w :a {})
                    (msg/workflow-done :w :s)
                    (msg/workflow-failed :w "e")
                    (msg/gate-result :w :g true)
                    (msg/gate-started :w :g)
                    (msg/tool-invoked :w :a :t)
                    (msg/tool-completed :w :a :t)
                    (msg/chain-started :c 3)
                    (msg/chain-step-started :c :s 0 :w)
                    (msg/chain-step-completed :c :s 0)
                    (msg/chain-step-failed :c :s 0 "e")
                    (msg/chain-completed :c 100 3)
                    (msg/chain-failed :c :s "e")]]
      (doseq [m messages]
        (is (vector? m) (str "Expected vector, got: " (type m)))
        (is (= 2 (count m)) (str "Expected 2 elements in " (first m)))
        (is (keyword? (first m)) (str "Expected keyword msg-type, got: " (first m)))))))
