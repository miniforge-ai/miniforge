;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.workflow-runner.dashboard-url-stream-test
  "`mf workflow run --dashboard-url` still hands the pipeline an event stream:
   gates record each phase decision on it, so without one every gated phase
   fails. The GC, manifest and archive collaborators are stubbed, and the
   stream has no sinks, so nothing is written or cleaned."
  (:require
   [ai.miniforge.cli.workflow-runner :as sut]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.execution :as execution]
   [ai.miniforge.cli.workflow-runner.gc-hooks :as gc-hooks]
   [ai.miniforge.cli.workflow-runner.lifecycle :as lifecycle]
   [ai.miniforge.cli.workflow-runner.setup :as setup]
   [ai.miniforge.event-stream.interface :as es]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} pipeline-stream
  "Run `run-workflow!` with `opts`; return the event stream the pipeline got."
  [opts local-stream]
  (let [seen (atom ::not-run)]
    (with-redefs [gc-hooks/run-gc-pass-best-effort! (fn [_repo-fn _gc-fn] nil)
                  gc-hooks/enqueue-workflow-gc-best-effort! (fn [_enqueue-fn _wid] nil)
                  es/create-event-stream (fn [& _] local-stream)
                  setup/resolve-workflow-interface (fn [] {:load-workflow (fn [_id _version] {})
                                                           :run-pipeline (fn [& _] {})})
                  setup/load-and-validate-workflow (fn [_load-fn _id _version] {})
                  context/resolve-input (fn [_opts] {})
                  execution/create-artifact-store (fn [_quiet] nil)
                  execution/execute-workflow-pipeline
                  (fn [_pipeline-fn _workflow _input _callbacks _store stream]
                    (reset! seen stream)
                    {:execution/status :completed})
                  lifecycle/start-workflow-manifest! (fn [_wid _stream] nil)
                  lifecycle/mark-manifest-terminal! (fn [_handle _status] nil)
                  lifecycle/event-stream-shutdown! (fn [_stream _wid _opts] nil)
                  lifecycle/archive-workflow-manifest! (fn [_handle _wid] nil)
                  lifecycle/finish-workflow-manifest! (fn [_handle] nil)]
      (binding [*out* (java.io.StringWriter.)
                *err* (java.io.StringWriter.)]
        (sut/run-workflow! "wf-dashboard" (assoc opts :quiet true))))
    @seen))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-dashboard-url-run-keeps-an-event-stream
  (let [local (es/create-event-stream {:sinks []})]
    (is (identical? local (pipeline-stream {:dashboard-url "http://localhost:7878"} local)))
    (is (identical? local (pipeline-stream {} local)) "the same stream as a run without it")))

(comment
  (clojure.test/run-tests 'ai.miniforge.cli.workflow-runner.dashboard-url-stream-test))
