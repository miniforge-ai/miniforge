;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.workflow-runner.spec-kanban-tracking-test
  "A work spec stays traceable through its kanban moves: its digest is
   taken before the move to in-progress, and the run's manifest follows
   it to work/failed."
  (:require [ai.miniforge.cli.workflow-runner.spec-kanban :as kanban]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-kanban
  "Run `f` with the kanban folders under a temp dir; `f` gets the dir."
  [f]
  (fs/with-temp-dir [dir {:prefix "kanban-tracking-"}]
    (let [root (str dir)
          prefix (str root "/work/")]
      (with-redefs [kanban/work-dirs {:in-progress (str root "/work/in-progress")
                                      :done (str root "/work/done")
                                      :failed (str root "/work/failed")}
                    kanban/work-spec? (fn [provenance]
                                        (str/starts-with? (str (:source-file provenance)) prefix))]
        (f root)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-spec-is-tracked-through-its-moves
  (with-kanban
    (fn [root]
      (let [spec (str root "/work/task.spec.edn")
            _ (fs/create-dirs (fs/parent spec))
            _ (spit spec "v1")
            digest (workflow/spec-file-hash spec)
            recorded (atom nil)
            [provenance input] (kanban/start-spec! {:spec/path spec} {:source-file spec})]
        (is (= spec (:spec/path input)) ":spec/path stays as given")
        (is (= (str (fs/absolutize (str root "/work/in-progress/task.spec.edn")))
               (:spec/location input)))
        (is (= digest (:spec/hash input)) "taken before the move")
        (with-redefs [workflow/record-spec-path! (fn [id path] (reset! recorded [id path]))]
          ;; The id the pipeline checkpointed under, not the CLI's own.
          (kanban/finish-spec! provenance {:execution/status :failed :execution/id "exec-1"}))
        (is (= ["exec-1" (str (fs/absolutize (str root "/work/failed/task.spec.edn")))]
               @recorded))))))

(deftest ^{:stratum 1} a-run-that-never-reached-the-pipeline-moves-without-a-record
  (with-kanban
    (fn [root]
      (let [spec (str root "/work/other.spec.edn")
            _ (fs/create-dirs (fs/parent spec))
            _ (spit spec "v1")
            recorded (atom nil)]
        (with-redefs [workflow/record-spec-path! (fn [& args] (reset! recorded args))]
          (is (some? (kanban/finish-spec! {:source-file spec} {:execution/status :failed}))))
        (is (nil? @recorded))))))

(comment ::spec-kanban-tracking)
