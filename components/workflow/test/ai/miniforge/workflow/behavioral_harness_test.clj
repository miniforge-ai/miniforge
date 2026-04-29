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

(ns ai.miniforge.workflow.behavioral-harness-test
  "Tests for the behavioral harness orchestrator."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.workflow.behavioral-harness :as sut]))

;;------------------------------------------------------------------------------ Helpers

(defn- make-stream
  "Create a no-sink in-memory event stream for tests."
  []
  (event-stream/create-event-stream {:sinks []}))

(defn- publish-test-event!
  "Publish a minimal test event to stream."
  [stream event-type]
  (event-stream/publish! stream {:event/type event-type
                                 :event/id   (random-uuid)
                                 :workflow/id :test-workflow}))

(defn- harness-subscriber-keys
  "Extract harness subscriber keywords from a stream atom."
  [stream]
  (->> (keys (:subscribers @stream))
       (filter #(str/starts-with? (name %) "behavioral-harness-"))))

;;------------------------------------------------------------------------------ resolve-harness-config

(deftest resolve-harness-config-returns-config-when-present
  (testing "returns :spec/behavioral-harness value from ctx"
    (let [config {:harness/type :command :harness/command "echo"}
          ctx    {:spec/behavioral-harness config}]
      (is (= config (sut/resolve-harness-config ctx))))))

(deftest resolve-harness-config-returns-nil-when-absent
  (testing "returns nil when :spec/behavioral-harness is not in ctx"
    (is (nil? (sut/resolve-harness-config {})))
    (is (nil? (sut/resolve-harness-config {:other/key :value})))))

;;------------------------------------------------------------------------------ run-behavioral-harness — shape

(deftest run-behavioral-harness-returns-required-keys
  (testing "result always contains the four required top-level keys"
    (let [config {:harness/type :command :harness/command "echo" :harness/args ["ok"]}
          result (sut/run-behavioral-harness config nil {})]
      (is (contains? result :behavioral/events))
      (is (contains? result :behavioral/harness-config))
      (is (contains? result :behavioral/duration-ms))
      (is (contains? result :behavioral/status)))))

(deftest run-behavioral-harness-preserves-config
  (testing ":behavioral/harness-config echoes the supplied config"
    (let [config {:harness/type :command :harness/command "echo"}
          result (sut/run-behavioral-harness config nil {})]
      (is (= config (:behavioral/harness-config result))))))

(deftest run-behavioral-harness-duration-is-non-negative
  (testing ":behavioral/duration-ms is a non-negative integer"
    (let [config {:harness/type :command :harness/command "echo"}
          result (sut/run-behavioral-harness config nil {})]
      (is (nat-int? (:behavioral/duration-ms result))))))

;;------------------------------------------------------------------------------ run-behavioral-harness — :command type

(deftest run-behavioral-harness-command-success
  (testing "successful command returns :completed status"
    (let [config {:harness/type :command :harness/command "echo" :harness/args ["hello"]}
          result (sut/run-behavioral-harness config nil {})]
      (is (= :completed (:behavioral/status result)))
      (is (not (contains? result :behavioral/error))))))

(deftest run-behavioral-harness-command-failure
  (testing "non-zero exit code returns :error status with error data"
    (let [config {:harness/type    :command
                  :harness/command "sh"
                  :harness/args    ["-c" "exit 1"]}
          result (sut/run-behavioral-harness config nil {})]
      (is (= :error (:behavioral/status result)))
      (is (contains? result :behavioral/error))
      (is (= :command-failed (get-in result [:behavioral/error :type]))))))

(deftest run-behavioral-harness-command-missing
  (testing "missing :harness/command returns :error status"
    (let [config {:harness/type :command}
          result (sut/run-behavioral-harness config nil {})]
      (is (= :error (:behavioral/status result)))
      (is (= :missing-command (get-in result [:behavioral/error :type]))))))

;;------------------------------------------------------------------------------ run-behavioral-harness — :workflow type

(deftest run-behavioral-harness-workflow-success
  (testing ":workflow type delegates to :generate-fn on ctx"
    (let [generate-calls (atom [])
          generate-fn    (fn [input]
                           (swap! generate-calls conj input)
                           {:result :generated})
          config         {:harness/type  :workflow
                          :harness/input {:prompt "run checks"}}
          ctx            {:generate-fn generate-fn}
          result         (sut/run-behavioral-harness config nil ctx)]
      (is (= :completed (:behavioral/status result)))
      (is (= 1 (count @generate-calls)))
      (is (= "run checks" (get-in @generate-calls [0 :prompt]))))))

(deftest run-behavioral-harness-workflow-missing-generate-fn
  (testing ":workflow type without :generate-fn returns :error"
    (let [config {:harness/type :workflow}
          result (sut/run-behavioral-harness config nil {})]
      (is (= :error (:behavioral/status result)))
      (is (= :missing-generate-fn (get-in result [:behavioral/error :type]))))))

(deftest run-behavioral-harness-workflow-generate-fn-throws
  (testing "exception from :generate-fn is caught and returned as anomaly data"
    (let [generate-fn (fn [_] (throw (ex-info "oops" {:code 42})))
          config      {:harness/type :workflow}
          ctx         {:generate-fn generate-fn}
          result      (sut/run-behavioral-harness config nil ctx)]
      (is (= :error (:behavioral/status result)))
      (is (= "oops" (get-in result [:behavioral/error :message])))
      (is (= {:code 42} (get-in result [:behavioral/error :data]))))))

;;------------------------------------------------------------------------------ run-behavioral-harness — unknown type

(deftest run-behavioral-harness-unknown-type
  (testing "unknown :harness/type returns :error status"
    (let [config {:harness/type :telekinesis}
          result (sut/run-behavioral-harness config nil {})]
      (is (= :error (:behavioral/status result)))
      (is (= :unknown-harness-type (get-in result [:behavioral/error :type])))
      (is (= :telekinesis (get-in result [:behavioral/error :harness/type]))))))

;;------------------------------------------------------------------------------ run-behavioral-harness — event stream

(deftest run-behavioral-harness-collects-events-from-stream
  (testing "events published during harness run are captured in :behavioral/events"
    (let [stream      (make-stream)
          generate-fn (fn [_]
                        (publish-test-event! stream :test/event-a)
                        (publish-test-event! stream :test/event-b)
                        {:result :done})
          config      {:harness/type :workflow}
          ctx         {:generate-fn generate-fn}
          result      (sut/run-behavioral-harness config stream ctx)]
      (is (= :completed (:behavioral/status result)))
      (is (= 2 (count (:behavioral/events result))))
      (is (= #{:test/event-a :test/event-b}
             (set (map :event/type (:behavioral/events result))))))))

(deftest run-behavioral-harness-unsubscribes-after-run
  (testing "subscriber is removed from stream after harness completes"
    (let [stream (make-stream)
          config {:harness/type :command :harness/command "echo"}
          _      (sut/run-behavioral-harness config stream {})]
      (is (empty? (harness-subscriber-keys stream))))))

(deftest run-behavioral-harness-nil-stream-is-safe
  (testing "nil event-stream is handled gracefully — no error"
    (let [config {:harness/type :command :harness/command "echo"}
          result (sut/run-behavioral-harness config nil {})]
      (is (= :completed (:behavioral/status result)))
      (is (= [] (:behavioral/events result))))))

(deftest run-behavioral-harness-unsubscribes-even-on-error
  (testing "subscriber is cleaned up even when harness errors"
    (let [stream      (make-stream)
          generate-fn (fn [_] (throw (ex-info "boom" {})))
          config      {:harness/type :workflow}
          ctx         {:generate-fn generate-fn}
          _           (sut/run-behavioral-harness config stream ctx)]
      (is (empty? (harness-subscriber-keys stream))))))

;;------------------------------------------------------------------------------ evaluate-behavioral-gate

(deftest evaluate-behavioral-gate-delegates-to-gate
  (testing "builds telemetry artifact and delegates to gate/check-gate :behavioral"
    (let [gate-calls (atom [])
          fake-gate  (fn [kw artifact _ctx]
                       (swap! gate-calls conj {:kw kw :artifact artifact})
                       {:passed? true :gate kw :errors []})
          harness-result {:behavioral/events         [{:event/type :x}]
                          :behavioral/harness-config {:harness/type :command}
                          :behavioral/duration-ms    42
                          :behavioral/status         :completed}]
      (with-redefs [gate/check-gate fake-gate]
        (let [gate-result (sut/evaluate-behavioral-gate harness-result {})]
          (is (= 1 (count @gate-calls)))
          (is (= :behavioral (:kw (first @gate-calls))))
          (is (= :behavioral (:gate gate-result)))
          (is (boolean? (:passed? gate-result))))))))

(deftest evaluate-behavioral-gate-telemetry-artifact-shape
  (testing "telemetry artifact contains all expected keys"
    (let [captured-artifact (atom nil)
          fake-gate         (fn [_kw artifact _ctx]
                              (reset! captured-artifact artifact)
                              {:passed? true :gate :behavioral :errors []})
          events            [{:event/type :a} {:event/type :b}]
          config            {:harness/type :command}
          harness-result    {:behavioral/events         events
                             :behavioral/harness-config config
                             :behavioral/duration-ms    99
                             :behavioral/status         :completed}]
      (with-redefs [gate/check-gate fake-gate]
        (sut/evaluate-behavioral-gate harness-result {}))
      (let [artifact @captured-artifact]
        (is (= events (:telemetry/events artifact)))
        (is (= 2 (:telemetry/event-count artifact)))
        (is (= 99 (:telemetry/duration-ms artifact)))
        (is (= config (:telemetry/harness-config artifact)))
        (is (= :completed (:behavioral/status artifact)))))))

(deftest evaluate-behavioral-gate-empty-events
  (testing "empty event list produces event-count of zero"
    (let [captured  (atom nil)
          fake-gate (fn [_kw artifact _ctx]
                      (reset! captured artifact)
                      {:passed? false :gate :behavioral :errors []})
          harness-result {:behavioral/events         []
                          :behavioral/harness-config {}
                          :behavioral/duration-ms    0
                          :behavioral/status         :error}]
      (with-redefs [gate/check-gate fake-gate]
        (sut/evaluate-behavioral-gate harness-result {}))
      (is (= 0 (:telemetry/event-count @captured))))))
