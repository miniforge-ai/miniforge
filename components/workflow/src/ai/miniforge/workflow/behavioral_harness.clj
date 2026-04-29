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

(ns ai.miniforge.workflow.behavioral-harness
  "Behavioral harness orchestrator for the monitor/observe phase.

   Implements the behavioral verification protocol (M1–M3):
     M1 – resolve harness config from workflow spec
     M2 – subscribe to event stream, run harness, collect events
     M3 – gate evaluation on observed telemetry

   Integration point: the monitor/observe phase calls these functions after a
   PR has landed to verify that the shipped behaviour matches the spec.

   Spec extension key: :spec/behavioral-harness
     {:type :bb-script | :workflow | :repl-eval
      :path \"<path-to-script>\"   ; for :bb-script
      :form \"(some-expr)\"         ; for :repl-eval
      :workflow-id :kw             ; for :workflow}"
  (:require
   [clojure.java.shell :as shell]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.gate.interface :as gate]))

;;------------------------------------------------------------------------------ Layer 0
;; Config resolution

(defn resolve-harness-config
  "Extract behavioral harness config from a workflow spec.

   Returns the value of :spec/behavioral-harness, or nil when the key is
   absent (i.e. the spec opts out of behavioral verification)."
  [spec]
  (:spec/behavioral-harness spec))

;;------------------------------------------------------------------------------ Layer 1
;; Harness execution

(defn execute-harness
  "Dispatch harness execution by :type.

   Internal function — not private so tests can rebind it via with-redefs.

   Supported types:
     :bb-script  – run a babashka script at :path; throws on non-zero exit
     :repl-eval  – eval a Clojure form string at :form; returns result
     :workflow   – reserved (not yet implemented)

   Returns a result map.  Throws ex-info on failure so that
   run-behavioral-harness can translate it to an anomaly map."
  [harness-config _ctx]
  (let [harness-type (:type harness-config)]
    (case harness-type
      :bb-script
      (let [path   (:path harness-config)
            result (shell/sh "bb" path)]
        (if (zero? (:exit result))
          {:harness/exit-code 0
           :harness/output    (:out result)}
          (throw (ex-info "Behavioral harness script exited with non-zero status"
                          {:exit-code (:exit result)
                           :stdout    (:out result)
                           :stderr    (:err result)
                           :path      path}))))

      :repl-eval
      (let [form-str (:form harness-config)]
        {:harness/result (eval (read-string form-str))})

      :workflow
      (throw (ex-info "Workflow harness type not yet implemented"
                      {:type      :workflow
                       :supported [:bb-script :repl-eval]}))

      (throw (ex-info "Unknown behavioral harness type"
                      {:type      harness-type
                       :supported [:bb-script :workflow :repl-eval]})))))

;;------------------------------------------------------------------------------ Layer 2
;; Orchestration

(defn run-behavioral-harness
  "Subscribe to event-stream, execute the harness, collect all events emitted
   during the run, then unsubscribe.

   Subscription is always cleaned up — on both success and error paths.

   Returns on success:
     {:behavioral/status      :completed
      :behavioral/events      [<event> ...]
      :behavioral/event-count N
      & harness-result-keys}

   Returns on error (never throws):
     {:anomaly/category       :anomalies/behavioral-harness-error
      :anomaly/message        \"<ex-message>\"
      :anomaly/data           {<ex-data>}
      :behavioral/status      :failed
      :behavioral/events      [<events collected before failure>]
      :behavioral/event-count N}"
  [harness-config event-stream ctx]
  (let [subscriber-id    (keyword "behavioral-harness" (str (random-uuid)))
        collected-events (atom [])]
    (es/subscribe! event-stream
                   subscriber-id
                   (fn [event] (swap! collected-events conj event)))
    (try
      (let [result (execute-harness harness-config ctx)]
        (es/unsubscribe! event-stream subscriber-id)
        (merge result
               {:behavioral/status      :completed
                :behavioral/events      @collected-events
                :behavioral/event-count (count @collected-events)}))
      (catch Exception ex
        (es/unsubscribe! event-stream subscriber-id)
        {:anomaly/category       :anomalies/behavioral-harness-error
         :anomaly/message        (ex-message ex)
         :anomaly/data           (or (ex-data ex) {})
         :behavioral/status      :failed
         :behavioral/events      @collected-events
         :behavioral/event-count (count @collected-events)}))))

;;------------------------------------------------------------------------------ Layer 3
;; Gate evaluation

(defn evaluate-behavioral-gate
  "Evaluate the :behavioral gate on an artifact using the gate component.

   Delegates directly to gate/check-gate so that the standard gate
   lifecycle events (started / passed / failed) are emitted.

   Arguments:
     artifact – artifact map to validate
     ctx      – execution context (must include :event-stream for gate events)

   Returns gate result: {:passed? bool :gate :behavioral :errors [...]}"
  [artifact ctx]
  (gate/check-gate :behavioral artifact ctx))

;;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Resolve config from a spec
  (resolve-harness-config {:spec/behavioral-harness {:type :bb-script
                                                      :path "test/harness/smoke.bb"}})
  ;; => {:type :bb-script :path "test/harness/smoke.bb"}

  (resolve-harness-config {:spec/name "my-workflow"})
  ;; => nil

  ;; Run harness with a no-op event stream (useful in dev)
  (let [stream (es/create-event-stream {:sinks []})]
    (run-behavioral-harness {:type :repl-eval :form "(+ 1 2)"} stream {}))
  ;; => {:harness/result 3 :behavioral/status :completed :behavioral/events [] ...}

  :leave-this-here)
