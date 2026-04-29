;; Copyright 2025-2026 miniforge.ai
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
  "Unit tests for the behavioral harness orchestrator.

   All tests use a no-sink event stream to avoid file I/O. The internal
   execute-harness function is rebound via with-redefs so harness tests
   remain fast and deterministic."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.workflow.behavioral-harness :as sut]
   [clojure.test :refer [deftest is testing]]))

;;------------------------------------------------------------------------------ Helpers

(defn- make-stream
  "Create an in-memory-only event stream with no file sinks."
  []
  (es/create-event-stream {:sinks []}))

(defn- publish-test-event!
  "Publish a minimal test event to stream."
  [stream event-type]
  (es/publish! stream {:event/type event-type
                       :workflow/id "test-workflow"
                       :event/id   (random-uuid)}))

;;------------------------------------------------------------------------------ resolve-harness-config

(deftest resolve-harness-config-returns-nil-when-missing-test
  (testing "returns nil when :spec/behavioral-harness key is absent from spec"
    (is (nil? (sut/resolve-harness-config {})))
    (is (nil? (sut/resolve-harness-config {:spec/name "my-workflow"
                                           :spec/phases [:plan :implement]})))
    (is (nil? (sut/resolve-harness-config nil)))))

(deftest resolve-harness-config-returns-config-when-present-test
  (testing "returns the harness config map when :spec/behavioral-harness is set"
    (let [harness-cfg {:type :bb-script :path "test/smoke.bb"}
          spec        {:spec/name              "my-workflow"
                       :spec/behavioral-harness harness-cfg}]
      (is (= harness-cfg (sut/resolve-harness-config spec)))))

  (testing "works for :repl-eval harness type"
    (let [harness-cfg {:type :repl-eval :form "(+ 1 2)"}]
      (is (= harness-cfg
             (sut/resolve-harness-config {:spec/behavioral-harness harness-cfg})))))

  (testing "returns truthy value so callers can use it as a presence check"
    (is (sut/resolve-harness-config {:spec/behavioral-harness {:type :workflow}}))))

;;------------------------------------------------------------------------------ run-behavioral-harness — happy path

(deftest run-behavioral-harness-subscribes-and-collects-events-test
  (testing "subscribes to the event stream and collects all events emitted during the run"
    (let [stream      (make-stream)
          harness-cfg {:type :bb-script :path "/fake/script.bb"}
          ;; Stub execute-harness to publish two events during execution
          stub-exec   (fn [_config _ctx]
                        (publish-test-event! stream :behavioral/check-started)
                        (publish-test-event! stream :behavioral/check-completed)
                        {:harness/exit-code 0})]
      (with-redefs [sut/execute-harness stub-exec]
        (let [result (sut/run-behavioral-harness harness-cfg stream {})]
          (testing "returns :completed status"
            (is (= :completed (:behavioral/status result))))
          (testing "collected events list matches events published during run"
            (is (= 2 (:behavioral/event-count result)))
            (is (= 2 (count (:behavioral/events result)))))
          (testing "event types are correct"
            (let [types (mapv :event/type (:behavioral/events result))]
              (is (= [:behavioral/check-started :behavioral/check-completed] types))))
          (testing "harness result keys are merged into result"
            (is (= 0 (:harness/exit-code result)))))))))

;;------------------------------------------------------------------------------ run-behavioral-harness — error path

(deftest run-behavioral-harness-returns-anomaly-on-error-test
  (testing "returns anomaly data map instead of throwing when harness execution fails"
    (let [stream      (make-stream)
          harness-cfg {:type :bb-script :path "/fake/script.bb"}
          error-data  {:exit-code 1 :stderr "assertion failed"}
          stub-exec   (fn [_config _ctx]
                        ;; Publish one event before failing
                        (publish-test-event! stream :behavioral/check-started)
                        (throw (ex-info "Harness script exited with non-zero status"
                                        error-data)))]
      (with-redefs [sut/execute-harness stub-exec]
        (let [result (sut/run-behavioral-harness harness-cfg stream {})]
          (testing "does not throw — returns a map"
            (is (map? result)))
          (testing ":behavioral/status is :failed"
            (is (= :failed (:behavioral/status result))))
          (testing "anomaly category is present"
            (is (= :anomalies/behavioral-harness-error (:anomaly/category result))))
          (testing "anomaly message is the exception message string"
            (is (string? (:anomaly/message result)))
            (is (re-find #"non-zero" (:anomaly/message result))))
          (testing "anomaly data carries ex-data payload"
            (is (= error-data (:anomaly/data result))))
          (testing "events emitted before the error are still captured"
            (is (= 1 (:behavioral/event-count result))))))))

  (testing "returns empty events list when harness throws immediately"
    (let [stream      (make-stream)
          harness-cfg {:type :bb-script :path "/nonexistent"}
          stub-exec   (fn [_config _ctx]
                        (throw (ex-info "instant failure" {:reason :not-found})))]
      (with-redefs [sut/execute-harness stub-exec]
        (let [result (sut/run-behavioral-harness harness-cfg stream {})]
          (is (= :failed (:behavioral/status result)))
          (is (= [] (:behavioral/events result)))
          (is (= 0 (:behavioral/event-count result))))))))

;;------------------------------------------------------------------------------ evaluate-behavioral-gate

(deftest evaluate-behavioral-gate-delegates-to-gate-check-gate-test
  (testing "delegates to gate/check-gate with :behavioral gate keyword"
    (let [captured  (atom nil)
          artifact  {:code "(defn foo [] 42)"}
          ctx       {:workflow/id "test-wf"}
          stub-gate (fn [gate-kw art c]
                      (reset! captured {:gate-kw gate-kw :artifact art :ctx c})
                      {:passed? true :gate gate-kw :errors []})]
      (with-redefs [gate/check-gate stub-gate]
        (let [result (sut/evaluate-behavioral-gate artifact ctx)]
          (testing "check-gate was called with :behavioral as the gate keyword"
            (is (= :behavioral (:gate-kw @captured))))
          (testing "artifact is passed through unchanged"
            (is (= artifact (:artifact @captured))))
          (testing "ctx is passed through unchanged"
            (is (= ctx (:ctx @captured))))
          (testing "returns gate result directly"
            (is (true? (:passed? result)))
            (is (= :behavioral (:gate result))))))))

  (testing "propagates gate failure result without throwing"
    (let [artifact  {:code "invalid"}
          stub-gate (fn [_gate-kw _art _ctx]
                      {:passed? false :gate :behavioral
                       :errors  [{:type :check-error :message "assertion violated"}]})]
      (with-redefs [gate/check-gate stub-gate]
        (let [result (sut/evaluate-behavioral-gate artifact {})]
          (is (false? (:passed? result)))
          (is (seq (:errors result))))))))

;;------------------------------------------------------------------------------ Subscription cleanup

(deftest event-stream-subscription-cleaned-up-after-harness-completes-test
  (testing "subscriber is removed from stream after successful harness run"
    (let [stream      (make-stream)
          harness-cfg {:type :bb-script :path "/fake"}
          stub-exec   (fn [_config _ctx] {:harness/exit-code 0})]
      (with-redefs [sut/execute-harness stub-exec]
        (sut/run-behavioral-harness harness-cfg stream {})
        (is (empty? (:subscribers @stream))
            "No subscribers should remain after harness completes"))))

  (testing "subscriber is removed from stream even when harness throws"
    (let [stream      (make-stream)
          harness-cfg {:type :bb-script :path "/fake"}
          stub-exec   (fn [_config _ctx]
                        (throw (ex-info "Harness failed" {:reason :crash})))]
      (with-redefs [sut/execute-harness stub-exec]
        (sut/run-behavioral-harness harness-cfg stream {})
        (is (empty? (:subscribers @stream))
            "Subscriber must be cleaned up on the error path too")))))
