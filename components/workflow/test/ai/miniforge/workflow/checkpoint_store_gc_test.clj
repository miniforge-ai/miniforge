;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-store-gc-test
  "Checkpoint GC: what goes, what stays, and the hold that keeps a run's
   events while its checkpoint exists."
  (:require [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.workflow.checkpoint-store-gc :as gc]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private day-ms (* 24 60 60 1000))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} checkpointed-run!
  "A checkpoint directory for a fresh run id under `root`, last changed
   `days` ago, with `manifest` (EDN text) as its manifest. Returns the id."
  [root days manifest]
  (let [id (random-uuid)
        dir (io/file root (str id))
        files [(io/file dir "manifest.edn") (io/file dir "phases" "plan.edn")]
        at (- (System/currentTimeMillis) (* days day-ms))]
    (doseq [f files] (io/make-parents f))
    (spit (first files) manifest)
    (spit (second files) "{:phase/result {:status :completed}}")
    (doseq [f (reverse (file-seq dir))] (.setLastModified ^java.io.File f at))
    id))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} gc-deletes-only-expired-unpinned-runs-without-live-events
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (fs/with-temp-dir [events-dir {:prefix "gc-events-"}]
       (let [expired (checkpointed-run! root 31 "{:workflow/id 1}")
             recent (checkpointed-run! root 2 "{:workflow/id 1}")
             pinned (checkpointed-run! root 90 "{:pinned? true}")
             unreadable (checkpointed-run! root 90 "{:workflow/id")
             live (checkpointed-run! root 90 "{:workflow/id 1}")
             _ (fs/create-dirs (es/workflow-dir (fs/file events-dir) live))
             other (doto (io/file root "not-a-run") (.mkdirs)
                     (.setLastModified (- (System/currentTimeMillis) (* 90 day-ms))))
             result (workflow/gc-checkpoints! {:checkpoint/root root
                                               :events-dir (fs/file events-dir)})]
         (testing "an unpinned run unchanged past the retention goes"
           (is (= [expired] (:deleted result)))
           (is (not (fs/exists? (io/file root (str expired))))))
         (testing "everything else stays, with the reason"
           (is (= {:recent [recent] :pinned [pinned] :unreadable [unreadable] :live [live]}
                  (:kept result)))
           (is (.isDirectory other) "not named by a run id: never touched"))
         (is (empty? (:failures result))))))))

(deftest ^{:stratum 2} retention-is-configurable
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [week-old (checkpointed-run! root 8 "{:workflow/id 1}")]
       (is (empty? (:deleted (workflow/gc-checkpoints! {:checkpoint/root root}))))
       (is (= [week-old] (:deleted (workflow/gc-checkpoints! {:checkpoint/root root
                                                              :max-age-days 7}))))))))

(deftest ^{:stratum 2} a-checkpoint-holds-its-runs-events
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [id (checkpointed-run! root 31 "{:workflow/id 1}")]
       (testing "while the checkpoint exists the event cleanup keeps the run's events"
         (is (true? (es/retention-hold ::gc/checkpoint (str id)))))
       (workflow/gc-checkpoints! {:checkpoint/root root})
       (testing "once the GC deletes it, they are released"
         (is (false? (es/retention-hold ::gc/checkpoint (str id)))))))))

(comment ::checkpoint-store-gc)
