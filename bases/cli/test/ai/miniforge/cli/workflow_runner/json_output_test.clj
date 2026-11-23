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
(ns ai.miniforge.cli.workflow-runner.json-output-test
  "`mf workflow run -o json` writes exactly one JSON document to stdout:
   the result, or an error object when the run throws. These tests drive
   the real `run-workflow!` and the real header and result printers,
   with the pipeline and manifest collaborators stubbed, and parse all of
   stdout as JSON."
  (:require
   [ai.miniforge.cli.workflow-runner :as sut]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.execution :as execution]
   [ai.miniforge.cli.workflow-runner.gc-hooks :as gc-hooks]
   [ai.miniforge.cli.workflow-runner.lifecycle :as lifecycle]
   [ai.miniforge.cli.workflow-runner.setup :as setup]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.response.interface :as response]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} completed-result
  "A result shaped like a real run's: the execution context, which holds
   an atom, a fn and a java.time value that cheshire cannot encode as-is."
  [status]
  {:execution/status status
   :execution/id #uuid "00000000-0000-0000-0000-000000000001"
   :execution/started-at (java.time.Instant/parse "2026-10-28T00:00:00Z")
   :execution/opts {:event-stream (atom {})}
   :execution/logger inc})

(defn- ^{:stratum 0} json-documents
  "Every top-level JSON value in `s`, in order. Throws on any text that is
   not JSON, such as a header line or ANSI colour codes."
  [s]
  (vec (json/parsed-seq (java.io.StringReader. s))))

(defn- ^{:stratum 0} run-capturing
  "Run `run-workflow!` with `opts`, `pipeline` as the pipeline fn and the
   manifest helpers stubbed. The pipeline publishes a lifecycle event, so
   live progress would print if it were on. `extra-redefs` (var -> fn) is
   merged over the stubs. Returns {:out stdout :err stderr :thrown
   exception-or-nil}."
  [opts pipeline & [extra-redefs]]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        stream (es/create-event-stream {:sinks []})
        wf-id (random-uuid)
        base {#'gc-hooks/run-gc-pass-best-effort! (fn [_repo-fn _gc-fn] nil)
              #'gc-hooks/enqueue-workflow-gc-best-effort! (fn [_enqueue-fn _wid] nil)
              #'setup/resolve-workflow-interface (fn [] {:load-workflow (fn [_id _version] {})
                                                         :run-pipeline pipeline})
              #'setup/load-and-validate-workflow (fn [_load-fn _id _version] {})
              #'context/resolve-input (fn [_opts] {})
              #'execution/create-artifact-store (fn [_quiet] nil)
              #'execution/execute-workflow-pipeline
              (fn [pipeline-fn _workflow _input _callbacks _store _stream]
                (es/publish! stream (es/workflow-started stream wf-id))
                (pipeline-fn))
              #'lifecycle/start-workflow-manifest! (fn [_wid _stream] nil)
              #'lifecycle/mark-manifest-terminal! (fn [_handle _status] nil)
              #'lifecycle/event-stream-shutdown! (fn [_stream _wid _opts] nil)
              #'lifecycle/archive-workflow-manifest! (fn [_handle _wid] nil)
              #'lifecycle/finish-workflow-manifest! (fn [_handle] nil)}
        thrown (with-redefs-fn (merge base extra-redefs)
                 (fn []
                   (binding [*out* out
                             *err* err]
                     (try+
                       (sut/run-workflow! "wf-json" (merge {:event-stream stream} opts))
                       nil
                       (catch Object _ (:throwable &throw-context))))))]
    {:out (str out) :err (str err) :thrown thrown}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} json-success-is-the-only-stdout-test
  (testing "a completed run: stdout is the result alone; header and progress are not on it"
    (let [{:keys [out err thrown]} (run-capturing {:output :json}
                                                  #(completed-result :completed))
          docs (json-documents out)]
      (is (nil? thrown))
      (is (= 1 (count docs)))
      (is (= "completed" (get (first docs) "execution/status")))
      (is (= "2026-10-28T00:00:00Z" (get (first docs) "execution/started-at")))
      (is (str/includes? err "Workflow Runner") "the header goes to stderr"))))

(deftest ^{:stratum 1} json-failed-run-is-the-only-stdout-test
  (testing "a run that ends failed: stdout is the failed result alone"
    (let [{:keys [out]} (run-capturing {:output :json} #(completed-result :failed))
          docs (json-documents out)]
      (is (= 1 (count docs)))
      (is (= "failed" (get (first docs) "execution/status"))))))

(deftest ^{:stratum 1} json-quiet-still-prints-the-result-test
  (testing "-q suppresses the header, never the JSON"
    (let [{:keys [out err]} (run-capturing {:output :json :quiet true}
                                           #(completed-result :completed))
          docs (json-documents out)]
      (is (= 1 (count docs)))
      (is (= "completed" (get (first docs) "execution/status")))
      (is (not (str/includes? err "Workflow Runner"))))))

(deftest ^{:stratum 1} json-throw-prints-one-error-document-test
  (testing "a run that throws: stdout is one error object, and the throw propagates"
    (let [{:keys [out thrown]}
          (run-capturing {:output :json}
                         #(response/throw-anomaly! :anomalies/not-found
                                                   "Workflow not found: wf-json"
                                                   {:workflow-id "wf-json"}))
          docs (json-documents out)]
      (is (some? thrown))
      (is (= 1 (count docs)))
      (is (= "error" (get (first docs) "status")))
      (is (= "Workflow not found: wf-json" (get (first docs) "error"))))))

(deftest ^{:stratum 1} json-cleanup-throw-prints-one-error-document-test
  (testing "cleanup throws after the pipeline completed: one error document, not result plus error"
    (let [{:keys [out thrown]}
          (run-capturing {:output :json}
                         #(completed-result :completed)
                         {#'lifecycle/finish-workflow-manifest!
                          (fn [_handle] (throw (ex-info "manifest IO failed" {})))})
          docs (json-documents out)]
      (is (some? thrown))
      (is (= 1 (count docs)))
      (is (= "manifest IO failed" (get (first docs) "error"))))))

(deftest ^{:stratum 1} json-turns-live-progress-off-test
  (testing "progress prints from whichever thread publishes, where stderr
            cannot be bound, so with -o json it is off"
    (let [progress-quiet (atom [])
          start-progress (fn [_stream quiet?]
                           (swap! progress-quiet conj quiet?)
                           (fn [] nil))]
      (run-capturing {:output :json} #(completed-result :completed)
                     {#'display/start-progress! start-progress})
      (run-capturing {:output :pretty} #(completed-result :completed)
                     {#'display/start-progress! start-progress})
      (is (= [true false] @progress-quiet)))))
