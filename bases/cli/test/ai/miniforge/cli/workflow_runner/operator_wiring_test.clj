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
   [ai.miniforge.cli.workflow-runner.control :as sut]
   [ai.miniforge.cli.workflow-runner.policy-evaluator :as policy-evaluator]
   [ai.miniforge.cli.workflow-runner.resume-launcher :as resume-launcher]
   [ai.miniforge.cli.workflow-runner.resume-records :as resume-records]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.interface :as operator]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-clean-operator-state
  "Fresh process singletons for `f`, with no files written for the runs it
   registers and no exit hooks left behind."
  [f]
  (let [context-state (var-get #'sut/meta-loop-ctx)
        consumer-state (var-get #'sut/operator-consumer-handle)
        original-context @context-state
        original-consumer @consumer-state]
    (reset! context-state nil)
    (reset! consumer-state nil)
    (try
      (with-redefs [resume-records/record-origin! (constantly nil)
                    resume-records/pending-launches (constantly [])
                    sut/stop-at-exit! (constantly nil)]
        (f))
      (finally
        (reset! context-state original-context)
        (reset! consumer-state original-consumer)))))

(deftest ^{:stratum 0} releasing-a-workflow-lets-go-of-its-origin
  (let [calls (atom [])]
    (with-redefs [operator/deregister-live-runner! #(swap! calls conj [:deregister %])
                  resume-records/release-origin! #(swap! calls conj [:release-origin %])]
      (sut/release-workflow-control! :workflow-a)
      (is (= [[:deregister :workflow-a] [:release-origin :workflow-a]] @calls)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} workflow-registration-starts-one-process-consumer
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
                      operator/register-resume-launcher! (constantly nil)
                      operator/register-policy-evaluator! (constantly nil)
                      operator/start-operator-consumer!
                      (fn [opts]
                        (swap! starts conj opts)
                        ::consumer-handle)]
          (#'sut/register-workflow-control! :workflow-a (atom {}) ::stream-a)
          (#'sut/register-workflow-control! :workflow-b (atom {}) ::stream-b)
          (is (= 1 (count @starts)))
          (is (= 2 (count @registrations)))
          (is (= [::degradation-manager ::degradation-manager]
                 @degradation-managers))
          (is (every? #(= #{:control-state :event-stream}
                           (set (keys (second %))))
                      @registrations))
          (is (= [::stream-a ::stream-b]
                 (mapv #(get-in % [1 :event-stream]) @registrations)))
          (is (= ::operator-stream (:stream (first @starts))))
          (testing "a runner's consumer leaves retries to a long-lived one"
            (let [accept? (:accept? (first @starts))]
              (is (not (accept? {:intervention/type :retry :intervention/target-id "w"})))
              (is (accept? {:intervention/type :acknowledge :intervention/target-id "a"}))))
          (is (identical? operator/live-intervention-stream
                          (:stream-for (first @starts)))))))))

(deftest ^{:stratum 1} consumer-startup-failure-deregisters-the-runner
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
                        operator/register-resume-launcher! (constantly nil)
                        operator/register-policy-evaluator! (constantly nil)
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
                 (#'sut/register-workflow-control!
                  :workflow-a (atom {}) ::workflow-stream)))
            (is (= [:workflow-a] @deregistrations))))))))

(deftest ^{:stratum 1} serve-and-runner-paths-register-the-same-process-handles
  (with-clean-operator-state
    (fn []
      (let [registered (atom {})
            starts (atom [])
            stops (atom [])
            origins (atom [])
            context {:event-stream ::operator-stream
                     :degradation-manager ::degradation-manager}]
        (with-redefs [agent/create-meta-loop-context (constantly context)
                      supervisory/attach! (constantly nil)
                      correlator/attach! (constantly nil)
                      es/create-event-stream (constantly ::operator-stream)
                      resume-launcher/launcher (constantly {:launch! identity})
                      resume-records/record-origin! #(swap! origins conj %)
                      operator/register-degradation-manager! #(swap! registered assoc :degradation %)
                      operator/register-resume-launcher! #(swap! registered assoc :launcher %)
                      operator/register-policy-evaluator! #(swap! registered assoc :evaluator %)
                      operator/register-live-runner! (constantly nil)
                      operator/start-operator-consumer! (fn [opts] (swap! starts conj opts) ::handle)
                      operator/stop-operator-consumer! #(swap! stops conj %)]
          (testing "a runnerless consumer registers every process handle and takes retries"
            (is (= ::handle (sut/start-process-control!)))
            (is (identical? operator/live-intervention-target? (:accept? (first @starts))))
            (is (= {:degradation ::degradation-manager
                    :launcher {:launch! identity}
                    :evaluator policy-evaluator/evaluate}
                   @registered)))
          (testing "a runner registers the same handles, records its origin, reuses the consumer"
            (reset! registered {})
            (sut/register-workflow-control! :workflow-a (atom {}) ::stream-a)
            (is (= #{:degradation :launcher :evaluator} (set (keys @registered))))
            (is (= [:workflow-a] @origins))
            (is (= 1 (count @starts))))
          (testing "the consumer reads the events root the rest of the process uses"
            (is (= (es/default-events-dir) (:events-dir (first @starts)))))
          (testing "stopping is idempotent"
            (sut/stop-process-control!)
            (sut/stop-process-control!)
            (is (= [::handle] @stops))))))))

(deftest ^{:stratum 1} a-thread-without-cli-arguments-keeps-the-registered-launcher
  (with-clean-operator-state
    (fn []
      (let [registered (atom [])]
        (with-redefs [agent/create-meta-loop-context (constantly {:event-stream ::operator-stream})
                      supervisory/attach! (constantly nil)
                      correlator/attach! (constantly nil)
                      es/create-event-stream (constantly ::operator-stream)
                      resume-launcher/launcher (constantly nil)
                      operator/register-degradation-manager! (constantly nil)
                      operator/register-policy-evaluator! (constantly nil)
                      operator/register-resume-launcher! #(swap! registered conj %)
                      operator/start-operator-consumer! (constantly ::handle)]
          (sut/start-process-control!)
          (is (empty? @registered) "nothing — not nil — is registered"))))))

(deftest ^{:stratum 1} a-starting-server-resumes-retries-left-dispatched
  (with-clean-operator-state
    (fn []
      (let [resumed (atom [])
            launch {:resume/run-id (random-uuid) :resume/intervention {:intervention/id 1}}]
        (with-redefs [agent/create-meta-loop-context (constantly {:event-stream ::operator-stream})
                      supervisory/attach! (constantly nil)
                      correlator/attach! (constantly nil)
                      es/create-event-stream (constantly ::operator-stream)
                      resume-launcher/launcher (constantly nil)
                      resume-records/pending-launches (constantly [launch])
                      operator/register-degradation-manager! (constantly nil)
                      operator/register-policy-evaluator! (constantly nil)
                      operator/verify-launched-resume! (fn [& args] (swap! resumed conj args))
                      operator/start-operator-consumer! (constantly ::handle)]
          (sut/start-process-control!)
          (is (= [[::operator-stream {:intervention/id 1} launch]] @resumed)))))))
