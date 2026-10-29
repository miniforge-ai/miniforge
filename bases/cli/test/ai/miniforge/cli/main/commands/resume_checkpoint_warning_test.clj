;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.resume-checkpoint-warning-test
  "`miniforge resume` tells the user about checkpoint files it could not
   read, even with --quiet."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.cli.main.commands.resume :as sut]
            [ai.miniforge.workflow-resume.interface :as wr]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private unreadable-phase
  (anomaly/anomaly :unavailable
                   "Checkpoint file /tmp/run/phases/implement.edn could not be read: EOF while reading"
                   {:checkpoint/path "/tmp/run/phases/implement.edn"}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unreadable-checkpoint-files-are-printed-even-when-quiet
  (let [output (with-redefs [wr/reconstruct-context
                             (fn [_events-dir _workflow-id]
                               {:completed? true
                                :checkpoint-read-failures [unreadable-phase]})]
                 (with-out-str (sut/resume-workflow (str (random-uuid)) {:quiet true})))]
    (is (str/includes? output "/tmp/run/phases/implement.edn could not be read"))))

(comment ::resume-checkpoint-warning)
