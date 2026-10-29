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
        (with-redefs [workflow/record-spec-path! (fn [id path & _] (reset! recorded [id path]))]
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

(deftest ^{:stratum 1} the-manifest-under-the-runs-own-root-follows-the-spec
  ;; A run checkpointed under a per-execution root, not the default one.
  (with-kanban
    (fn [root]
      (fs/with-temp-dir [checkpoints {:prefix "kanban-root-"}]
        (let [spec (str root "/work/rooted.spec.edn")
              _ (fs/create-dirs (fs/parent spec))
              _ (spit spec "v1")
              run-id (random-uuid)
              manifest (fs/path checkpoints (str run-id) "manifest.edn")
              _ (fs/create-dirs (fs/parent manifest))
              _ (spit (str manifest) (pr-str {:workflow/id run-id
                                              :workflow/spec-path spec
                                              :workflow/spec-hash (workflow/spec-file-hash spec)}))]
          (kanban/finish-spec! {:source-file spec}
                               {:execution/status :failed
                                :execution/id run-id
                                :execution/checkpoint-root (str checkpoints)})
          (is (= (str (fs/absolutize (str root "/work/failed/rooted.spec.edn")))
                 (:workflow/spec-path (read-string (slurp (str manifest)))))))))))

(comment ::spec-kanban-tracking)
