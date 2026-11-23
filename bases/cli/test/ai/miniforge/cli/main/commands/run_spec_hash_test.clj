;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.run-spec-hash-test
  "`mf run <spec>` records the digest of the spec text it parsed: the file
   is read once, so an edit made after parsing cannot leave the checkpoint
   manifest describing a spec that did not run."
  (:require [ai.miniforge.cli.main.commands.run :as run]
            [ai.miniforge.cli.main.display :as display]
            [ai.miniforge.cli.workflow-runner :as workflow-runner]
            [ai.miniforge.cli.workflow-runner.context :as context]
            [ai.miniforge.cli.workflow-runner.spec-kanban :as kanban]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private parsed-text
  (pr-str {:spec/title "One read"
           :spec/description "The spec as it was when the run parsed it."
           :spec/intent {:type :general}}))

(def ^{:stratum 0} ^:private edited-text
  (pr-str {:spec/title "Edited later"
           :spec/description "Written after parsing, before the run recorded its hash."
           :spec/intent {:type :general}}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} the-recorded-hash-describes-the-parsed-spec
  (fs/with-temp-dir [dir {:prefix "run-spec-hash-"}]
    (let [path (str (fs/path dir "task.spec.edn"))
          started (atom nil)]
      (spit path parsed-text)
      (with-redefs [workflow-runner/run-workflow-from-spec! (fn [spec _opts] (reset! started spec) {})
                    display/print-info (fn [& _] nil)]
        (#'run/run-spec-workflow path {}))
      ;; The file changes between the parse and the point where the run
      ;; takes the spec's digest.
      (spit path edited-text)
      (let [input (context/spec->workflow-input @started)
            [_ recorded] (kanban/start-spec! input (:spec/provenance @started))]
        (is (= (workflow/spec-content-hash parsed-text) (:spec/hash recorded)))
        (is (not= (workflow/spec-file-hash path) (:spec/hash recorded)))))))

(comment ::run-spec-hash)
