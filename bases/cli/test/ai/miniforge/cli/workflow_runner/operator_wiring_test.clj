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

(ns ai.miniforge.cli.workflow-runner.operator-wiring-test
  (:require
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [ai.miniforge.cli.workflow-runner :as sut]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.interface :as operator]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- with-clean-operator-state
  [f]
  (let [context-state (var-get #'sut/meta-loop-ctx)
        consumer-state (var-get #'sut/operator-consumer-handle)
        original-context @context-state
        original-consumer @consumer-state]
    (reset! context-state nil)
    (reset! consumer-state nil)
    (try
      (f)
      (finally
        (reset! context-state original-context)
        (reset! consumer-state original-consumer)))))

;------------------------------------------------------------------------------ Layer 1

(deftest workflow-registration-starts-one-process-consumer
  (with-clean-operator-state
    (fn []
      (let [starts (atom [])
            registrations (atom [])
            degradation-managers (atom [])
            context {:event-stream ::operator-stream
                     :degradation-manager ::degradation-manager}]
        (with-redefs [es/create-event-stream (constantly ::operator-stream)
                      supervisory/attach! (constantly nil)
                      correlator/attach! (constantly nil)
                      agent/create-meta-loop-context (constantly context)
                      operator/register-live-runner!
                      (fn [workflow-id handles]
                        (swap! registrations conj [workflow-id handles]))
                      operator/register-degradation-manager!
                      (fn [manager]
                        (swap! degradation-managers conj manager))
                      operator/start-operator-consumer!
                      (fn [opts]
                        (swap! starts conj opts)
                        ::consumer-handle)]
          (#'sut/register-workflow-control! :workflow-a (atom {}))
          (#'sut/register-workflow-control! :workflow-b (atom {}))
          (is (= 1 (count @starts)))
          (is (= 2 (count @registrations)))
          (is (= [::degradation-manager ::degradation-manager]
                 @degradation-managers))
          (is (every? #(= #{:control-state} (set (keys (second %))))
                      @registrations))
          (is (= ::operator-stream (:stream (first @starts))))
          (is (identical? operator/live-intervention-target?
                          (:accept? (first @starts)))))))))

(deftest consumer-startup-failure-deregisters-the-runner
  (testing "registration is rolled back before startup failure propagates"
    (with-clean-operator-state
      (fn []
        (let [deregistrations (atom [])
              context {:event-stream ::operator-stream
                       :degradation-manager ::degradation-manager}]
          (with-redefs [es/create-event-stream (constantly ::operator-stream)
                        supervisory/attach! (constantly nil)
                        correlator/attach! (constantly nil)
                        agent/create-meta-loop-context (constantly context)
                        operator/register-degradation-manager! (constantly nil)
                        operator/register-live-runner! (constantly nil)
                        operator/deregister-live-runner!
                        (fn [workflow-id]
                          (swap! deregistrations conj workflow-id))
                        operator/start-operator-consumer!
                        (fn [_opts]
                          (throw (ex-info "consumer startup failed" {})))]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"consumer startup failed"
                 (#'sut/register-workflow-control! :workflow-a (atom {}))))
            (is (= [:workflow-a] @deregistrations))))))))
