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
(ns ai.miniforge.cli.workflow-runner.execution-test
  "Tests for the sandbox-setup-failure branch of `execute-with-events`.

   The branch short-circuits the pipeline and synthesises its own
   workflow result. That result has to speak the same `:execution/*`
   vocabulary the pipeline emits, otherwise every downstream consumer
   (`phase/succeeded?`, the completion event, the pretty summary) reads
   a statusless, errorless map and reports the run as neither failed nor
   explained.

   `execute-with-events` also owns the artifact store's release: the
   caller opens the store before sandbox setup and nothing after the
   call closes it, so every exit path — completion, sandbox failure,
   throw — must release it here."
  (:require
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.execution :as sut]
   [ai.miniforge.cli.workflow-runner.lifecycle :as lifecycle]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase.interface :as phase]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private sandbox-error-message
  "Message carried by the fixture sandbox failure."
  "container runtime unreachable")

(defn- ^{:stratum 0} run-sandbox-failure
  "Drive `execute-with-events` down the sandbox-failure branch against a
   real event stream. Returns
   `{:result <workflow result> :events [...] :out <printed summary>}`."
  [context opts]
  (let [stream (es/create-event-stream {:sinks []})
        captured (atom [])
        result (atom nil)]
    (es/subscribe! stream :execution-test (fn [event] (swap! captured conj event)))
    (let [out (with-out-str
                (reset! result (sut/execute-with-events
                                {:run-pipeline (fn [& _]
                                                 (throw (ex-info "pipeline must not run" {})))
                                 :context context
                                 :event-stream stream
                                 :workflow-id (random-uuid)
                                 :opts opts})))]
      {:result @result :events @captured :out out})))

(defn- ^{:stratum 0} execute-recording-closes
  "Run `execute-with-events` with `overrides` merged over a minimal input,
   recording every store handed to `artifact/close-store`. Returns
   {:result ... :closed [...]} on normal return, {:thrown ... :closed [...]}
   when the call throws."
  [overrides]
  (let [closed (atom [])
        base {:run-pipeline (fn [_workflow _input _callbacks]
                              {:execution/status :completed})
              :workflow {}
              :workflow-input {}
              :context {}
              :artifact-store ::sentinel-store
              :event-stream nil
              :workflow-id "wf-execution-test"
              :sandbox-cleanup nil
              :opts {:quiet true}}
        outcome (with-redefs-fn {#'artifact/close-store (fn [store]
                                                          (swap! closed conj store)
                                                          nil)
                                 #'lifecycle/publish-completion-event (fn [_stream _id _result] nil)
                                 #'lifecycle/publish-failure-event! (fn [_stream _id _type _msg] nil)
                                 #'display/print-result (fn [_result _opts] nil)}
                  (fn []
                    (try+
                      {:result (sut/execute-with-events (merge base overrides))}
                      (catch Object thrown
                        {:thrown thrown}))))]
    (assoc outcome :closed @closed)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} sandbox-error-context
  "Context as `sandbox/setup-sandbox-context` builds it when
   `prepare-sandbox` returns a non-ok result: the dag error result
   itself, stashed under `:sandbox-error`."
  ([] (sandbox-error-context (dag/err :sandbox-prep-failed sandbox-error-message)))
  ([sandbox-error] {:sandbox-error sandbox-error}))

(deftest ^{:stratum 1} execute-with-events-closes-store-once-on-success-test
  (testing "completion path releases the artifact store exactly once"
    (let [{:keys [result closed]} (execute-recording-closes {})]
      (is (= :completed (:execution/status result)))
      (is (= [::sentinel-store] closed)))))

(deftest ^{:stratum 1} execute-with-events-closes-store-when-pipeline-throws-test
  (testing "a pipeline exception still releases the artifact store before rethrowing"
    (let [{:keys [result thrown closed]} (execute-recording-closes
                                          {:run-pipeline (fn [_workflow _input _callbacks]
                                                           (throw (ex-info "pipeline exploded" {})))})]
      (is (nil? result))
      (is (some? thrown))
      (is (= [::sentinel-store] closed)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} sandbox-failure-result-uses-canonical-execution-keys
  (testing "the synthesised result is a canonical failed workflow result"
    (let [{:keys [result]} (run-sandbox-failure (sandbox-error-context) {:quiet true})]
      (is (= :failed (:execution/status result)))
      (is (= [{:type :sandbox-setup-failed :message sandbox-error-message}]
             (:execution/errors result)))
      (is (not (contains? result :success?))
          "the runner-local :success?/:errors shape is gone — consumers
           read :execution/status and :execution/errors")
      (is (not (contains? result :errors))))))

(deftest ^{:stratum 2} sandbox-failure-result-is-a-failure-to-phase-predicates
  (testing "phase/succeeded? classifies the sandbox failure as a failure"
    (let [{:keys [result]} (run-sandbox-failure (sandbox-error-context) {:quiet true})]
      (is (false? (phase/succeeded? result)))
      (is (true? (phase/failed? result))
          "a statusless map is merely not-succeeded; :execution/status
           :failed makes the failure positively detectable"))))

(deftest ^{:stratum 2} sandbox-failure-falls-back-to-printing-an-unshaped-error
  ;; Deliberately NOT the producer's shape: every `dag/err` path carries
  ;; a map `:error` with a `:message`. This pins the defensive fallback
  ;; that keeps the pre-existing printed form if that ever stops holding.
  (testing "a sandbox error without a nested :message still yields a message"
    (let [sandbox-error {:ok? false :error :runtime-missing}
          {:keys [result]} (run-sandbox-failure (sandbox-error-context sandbox-error)
                                                {:quiet true})]
      (is (= [{:type :sandbox-setup-failed :message ":runtime-missing"}]
             (:execution/errors result))))))

(deftest ^{:stratum 2} sandbox-failure-publishes-a-workflow-failed-event
  (testing "the completion event is a failure carrying the sandbox message"
    (let [{:keys [events]} (run-sandbox-failure (sandbox-error-context) {:quiet true})
          failed (filterv #(= :workflow/failed (:event/type %)) events)]
      (is (= 1 (count events))
          "exactly one completion event; the finally must not add a
           cancellation event on top of it")
      (is (= 1 (count failed)))
      (is (= sandbox-error-message (:workflow/failure-reason (first failed)))
          "the sandbox message reaches the event, not
           'Workflow ended with status: unknown'"))))

(deftest ^{:stratum 2} sandbox-failure-prints-the-error-in-the-pretty-summary
  (testing "the pretty summary lists the sandbox error"
    (let [{:keys [out]} (run-sandbox-failure (sandbox-error-context) {:output :pretty})]
      (is (str/includes? out sandbox-error-message)
          "errors only render when they arrive under :execution/errors"))))

(deftest ^{:stratum 2} execute-with-events-closes-store-on-sandbox-failure-test
  (testing "sandbox-setup failure reports the error and releases the artifact store"
    (let [{:keys [result thrown closed]} (execute-recording-closes
                                          {:context (sandbox-error-context)
                                           :run-pipeline (fn [_workflow _input _callbacks]
                                                           (throw (ex-info "pipeline must not run after sandbox failure" {})))})]
      (is (nil? thrown))
      (is (= :failed (:execution/status result)))
      (is (= :sandbox-setup-failed (get-in result [:execution/errors 0 :type])))
      (is (= [::sentinel-store] closed)))))
