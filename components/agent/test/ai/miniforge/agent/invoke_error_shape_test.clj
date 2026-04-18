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

(ns ai.miniforge.agent.invoke-error-shape-test
  "Regression: invoke-impl's catch branch MUST produce the canonical
   response/error shape ({:error {:message string :data map}}), not
   hand-rolled {:error <string>}.

   Observed 2026-04-18 workflow 60cf2407: planner threw an anomaly,
   invoke-impl caught it and returned :error as a string, and the
   downstream DAG skip diagnostic's summarize-error couldn't pull the
   message (because (:message 'some string') is nil). Fix the
   producer, not the consumer."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.specialized :as specialized]
   [ai.miniforge.agent.interface :as agent]))

(defn- throwing-agent [ex]
  (specialized/create-base-agent
    {:role :planner
     :system-prompt "spy"
     :invoke-fn (fn [_ctx _input] (throw ex))
     :validate-fn (fn [_] {:valid? true})
     :repair-fn (fn [output _ _] output)}))

(deftest invoke-catch-produces-canonical-error-shape
  (testing "invoke-impl's catch returns {:status :error :error {:message :data ...}}"
    (let [ex (ex-info "boom" {:cause :test})
          result (agent/invoke (throwing-agent ex) {} {})]
      (is (= :error (:status result)))
      (is (map? (:error result))
          ":error MUST be a map (canonical response/error shape)")
      (is (= "boom" (:message (:error result))))
      (is (map? (:data (:error result)))
          ":error :data MUST be a map"))))

(deftest invoke-catch-preserves-ex-data
  (testing "ex-data from the thrown exception is preserved on :error :data"
    (let [ex (ex-info "llm timeout" {:provider :anthropic :retry? true})
          result (agent/invoke (throwing-agent ex) {} {})]
      (is (= :anthropic (get-in result [:error :data :provider])))
      (is (true? (get-in result [:error :data :retry?]))))))

(deftest invoke-catch-records-role-on-data
  (testing ":error :data carries :role so event sinks can attribute the failure"
    (let [ex (ex-info "boom" {})
          result (agent/invoke (throwing-agent ex) {} {})]
      (is (= :planner (get-in result [:error :data :role]))))))

(deftest invoke-catch-records-duration-at-top-level
  (testing ":duration-ms lives at the top level of the response (per response/error opts)"
    (let [ex (ex-info "boom" {})
          result (agent/invoke (throwing-agent ex) {} {})]
      (is (integer? (get-in result [:metrics :duration-ms]))))))

(deftest invoke-catch-message-not-string-under-error
  (testing "smoke test of the observed regression: :error is NOT a string"
    (let [ex (ex-info "some message" {})
          result (agent/invoke (throwing-agent ex) {} {})]
      (is (not (string? (:error result)))
          "if :error is a string here, summarize-error can't read it"))))
