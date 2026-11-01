;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-store-gc-test
  "Checkpoint GC: what goes, what stays, and the hold that keeps a run's
   events while its checkpoint exists."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.workflow.checkpoint-store-gc :as gc]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private day-ms (* 24 60 60 1000))

(defn- ^{:stratum 0} plan-written
  "The `:workflow/checkpoint-written` event run `id` publishes for a :plan
   checkpoint at `path` under `root`."
  [root id path]
  (es/checkpoint-written (es/create-event-stream {:sinks []}) id :plan path 1 root))

(defn- ^{:stratum 0} archive-event!
  "Archive `event` as run `id`'s only event under `events-dir`."
  [events-dir id event]
  (let [file (io/file events-dir "archived" (str id) "0000000000000001__000000000000.transit.json")]
    (io/make-parents file)
    (spit file (es/serialize-event event))))

(deftest ^{:stratum 0} an-unreadable-event-file-holds-the-run
  ;; The file that did not read may be the one naming the run's root.
  (fs/with-temp-dir [events-dir {:prefix "gc-events-"}]
    (let [id (random-uuid)
          bad (io/file (str events-dir) "archived" (str id) "0000000000000001__000000000000.transit.json")]
      (io/make-parents bad)
      (spit bad "not transit")
      (is (true? (es/retention-hold ::gc/checkpoint (str id) (fs/file events-dir)))))))

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

(deftest ^{:stratum 1} root-lookup-passes-over-an-unreadable-event
  (fs/with-temp-dir [alternate {:prefix "gc-alternate-root-"}]
    (fs/with-temp-dir [events-dir {:prefix "gc-events-"}]
      (let [id (random-uuid)
            dir (io/file (str events-dir) "archived" (str id))
            newest (io/file dir "0000000000000002__000000000000.transit.json")]
        (archive-event! (fs/file events-dir) id
                        (plan-written (str alternate) id
                                      (str (fs/path alternate (str id) "phases" "plan.edn"))))
        (spit newest "not transit")
        (testing "an older readable announcement names the root"
          (is (= (str alternate) (gc/run-checkpoint-root id (fs/file events-dir)))))
        (testing "with none readable the root is unknown, not the default"
          (fs/delete (io/file dir "0000000000000001__000000000000.transit.json"))
          (is (anomaly/anomaly? (gc/run-checkpoint-root id (fs/file events-dir)))))))))

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

(deftest ^{:stratum 2} a-symlink-inside-a-run-does-not-make-it-recent
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (fs/with-temp-dir [outside {:prefix "gc-outside-"}]
       (let [id (checkpointed-run! root 31 "{:workflow/id 1}")
             run-dir (io/file root (str id))
             fresh (doto (io/file (str outside) "fresh") (.mkdirs))
             _ (spit (io/file fresh "new.txt") "x")
             link (fs/create-sym-link (fs/path run-dir "linked") (fs/path fresh))]
         (.setLastModified run-dir (- (System/currentTimeMillis) (* 31 day-ms)))
         (is (= [id] (:deleted (workflow/gc-checkpoints! {:checkpoint/root root}))))
         (is (not (fs/exists? link {:nofollow-links true})))
         (is (fs/exists? (io/file fresh "new.txt")) "the link target is not deleted"))))))

(deftest ^{:stratum 2} a-checkpoint-holds-its-runs-events
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (fs/with-temp-dir [events-dir {:prefix "gc-events-"}]
       (let [id (checkpointed-run! root 31 "{:workflow/id 1}")
             held? #(es/retention-hold ::gc/checkpoint (str id) (fs/file events-dir))]
         (testing "while the checkpoint exists the event cleanup keeps the run's events"
           (is (true? (held?))))
         (workflow/gc-checkpoints! {:checkpoint/root root})
         (testing "once the GC deletes it, they are released"
           (is (false? (held?)))))))))

(deftest ^{:stratum 2} the-hold-finds-a-run-under-another-root-through-its-events
  ;; The default root is not redefined: the run lives only under `alternate`.
  (fs/with-temp-dir [alternate {:prefix "gc-alternate-root-"}]
    (fs/with-temp-dir [events-dir {:prefix "gc-events-"}]
      (let [id (checkpointed-run! (str alternate) 31 "{:workflow/id 1}")
            held? #(es/retention-hold ::gc/checkpoint (str id) (fs/file events-dir))]
        (testing "without the run's events naming the root, nothing holds"
          (is (false? (held?))))
        ;; An event written before announcements carried the root: the
        ;; hold infers it from the phase file's path.
        (archive-event! (fs/file events-dir) id
                        (dissoc (plan-written (str alternate) id
                                              (str (fs/path alternate (str id) "phases" "plan.edn")))
                                :checkpoint/root))
        (testing "its checkpoint-written event leads the hold to the root it used"
          (is (true? (held?))))
        (workflow/gc-checkpoints! {:checkpoint/root (str alternate)})
        (testing "and releases once that checkpoint is gone"
          (is (false? (held?))))))))

(deftest ^{:stratum 2} the-hold-reads-the-announced-root-not-the-file-path
  ;; A symlinked `phases` directory publishes the phase file outside the
  ;; run directory; the root the event carries still finds the run.
  (fs/with-temp-dir [alternate {:prefix "gc-alternate-root-"}]
    (fs/with-temp-dir [events-dir {:prefix "gc-events-"}]
      (let [id (checkpointed-run! (str alternate) 31 "{:workflow/id 1}")]
        (archive-event! (fs/file events-dir) id
                        (plan-written (str alternate) id
                                      (str (fs/path alternate "linked-phases" "plan.edn"))))
        (is (true? (es/retention-hold ::gc/checkpoint (str id) (fs/file events-dir))))
        (is (= (str alternate) (gc/run-checkpoint-root id (fs/file events-dir))))))))

(comment ::checkpoint-store-gc)
