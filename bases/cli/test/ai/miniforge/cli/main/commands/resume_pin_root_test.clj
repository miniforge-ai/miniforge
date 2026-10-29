;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.resume-pin-root-test
  "`mf resume <id> --pin` pins the run where its checkpoint lives: under
   the root its events announce when the default root does not hold it."
  (:require [ai.miniforge.automation-edge-correlator.interface :as correlator]
            [ai.miniforge.cli.main.commands.resume :as sut]
            [ai.miniforge.cli.main.display :as display]
            [ai.miniforge.cli.workflow-runner.context :as context]
            [ai.miniforge.cli.workflow-runner.control :as control]
            [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.supervisory-state.interface :as supervisory]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow-resume.interface :as wr]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} announce!
  "Archive under `events-dir` the checkpoint-written event run `id` published
   for a checkpoint under `root`."
  [events-dir root id]
  (let [event (es/checkpoint-written (es/create-event-stream {:sinks []}) id :plan
                                     (str (fs/path root (str id) "phases" "plan.edn")) 1)
        file (io/file (str events-dir) "archived" (str id) "0000000000000001__000000000000.transit.json")]
    (io/make-parents file)
    (spit file (es/serialize-event event))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} pin-goes-to-the-root-the-run-announced
  (fs/with-temp-dir [root {:prefix "pin-root-"}]
    (fs/with-temp-dir [events-dir {:prefix "pin-root-events-"}]
      (let [id (random-uuid)
            pinned (atom nil)]
        (fs/create-dirs (fs/path root (str id)))
        (spit (str (fs/path root (str id) "manifest.edn")) "{}")
        (announce! events-dir (str root) id)
        (with-redefs [sut/events-dir (str events-dir)
                      wr/reconstruct-context (fn [_ _] {:completed? true})
                      workflow/pin-checkpoint! (fn [& args] (reset! pinned args) "/pinned")
                      display/print-info (fn [& _] nil)
                      context/create-llm-client (fn [_ _ _] :llm-client)
                      es/create-event-stream (fn [] :event-stream)
                      supervisory/attach! (fn [_] nil)
                      correlator/attach! (fn [_] nil)
                      control/register-workflow-control! (fn [_ _ _] nil)
                      control/release-workflow-control! (fn [_] nil)]
          (sut/resume-workflow (str id) {:pin true :quiet true}))
        (is (= [(str id) {:checkpoint/root (str root)}] @pinned))))))

(comment ::resume-pin-root)
