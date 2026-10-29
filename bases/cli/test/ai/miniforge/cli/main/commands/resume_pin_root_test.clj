;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.resume-pin-root-test
  "`mf resume <id> --pin` pins the run where its checkpoint lives: under
   the root its events announce when the default root does not hold it. A
   failed lookup is a pin warning, and the resume goes on."
  (:require [ai.miniforge.automation-edge-correlator.interface :as correlator]
            [ai.miniforge.cli.main.commands.resume :as sut]
            [ai.miniforge.cli.main.display :as display]
            [ai.miniforge.cli.workflow-runner.context :as context]
            [ai.miniforge.cli.workflow-runner.control :as control]
            [ai.miniforge.config.interface :as config]
            [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.supervisory-state.interface :as supervisory]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow-resume.interface :as wr]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(deftest ^{:stratum 0} an-unlistable-run-directory-warns-and-the-resume-goes-on
  (fs/with-temp-dir [root {:prefix "pin-root-"}]
    (fs/with-temp-dir [events-dir {:prefix "pin-root-events-"}]
      (let [id (random-uuid)
            dir (fs/path root (str id))
            printed (atom [])]
        (fs/create-dirs dir)
        (spit (str (fs/path dir "manifest.edn")) "{}")
        ;; Enterable, so known files still read, but not listable.
        (fs/set-posix-file-permissions dir "-wx------")
        (try
          (when-not (fs/readable? dir)
            (with-redefs [sut/events-dir (str events-dir)
                          config/load-merged-config (constantly {:workflow {:checkpoint-root (str root)}})
                          wr/reconstruct-context (fn [_ _] {:completed? true})
                          display/print-info (fn [message] (swap! printed conj message))]
              (sut/resume-workflow (str id) {:pin true :quiet true}))
            (is (some #(str/includes? % (str "could not pin the checkpoint of run " id)) @printed))
            (is (some #{"Workflow already completed successfully."} @printed)))
          (finally (fs/set-posix-file-permissions dir "rwx------")))))))

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
