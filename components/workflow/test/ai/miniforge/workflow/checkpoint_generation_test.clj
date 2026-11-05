;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-generation-test
  "A checkpoint write is one generation: its files are forced to disk in
   a new generation directory and the manifest naming them is published
   last, so a write that dies part way leaves the previous checkpoint."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.boundary.checkpoint-file :as checkpoint-file]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-store-gc :as gc]
            [ai.miniforge.workflow.checkpoint-store-lock :as checkpoint-lock]
            [ai.miniforge.workflow.checkpoint-store-paths :as paths]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failing-manifest-write
  "A `checkpoint-file/write!` that dies when it reaches the manifest."
  [write!]
  (fn [target & more]
    (if (= paths/manifest-filename (str (fs/file-name target)))
      (throw (IOException. "crash before the manifest"))
      (apply write! target more))))

(defn- ^{:stratum 0} generation-of [path]
  (str (fs/file-name (fs/parent path))))

(defn- ^{:stratum 0} implement-completed [ctx]
  (-> ctx
      (assoc :execution/current-phase :implement)
      (assoc-in [:execution/phase-results :implement] {:status :completed})))

(defn- ^{:stratum 0} verify-forced-before-manifest [root]
  (let [events (atom [])
        fill! checkpoint-file/fill!
        sync! checkpoint-file/sync-directory!
        written (with-redefs [checkpoint-file/fill! (fn [temporary text]
                                                      (swap! events conj [:fill (str (fs/parent temporary))])
                                                      (fill! temporary text))
                              checkpoint-file/sync-directory! (fn [directory]
                                                                (swap! events conj [:sync (str directory)])
                                                                (sync! directory))]
                  (store/persist-execution-state! (fixture/plan-completed-context root {})))
        run-dir (str (fs/parent (:checkpoint/manifest-path written)))
        generation (str (fs/parent (:checkpoint/machine-snapshot-path written)))
        at (fn [pred] (keep-indexed (fn [i event] (when (pred event) i)) @events))
        manifest-fill (first (at #(= [:fill run-dir] %)))
        generation-syncs (at (fn [[kind path]] (and (= :sync kind) (str/starts-with? path generation))))]
    (testing "the snapshot's and the phase's directories are forced"
      (is (= 2 (count generation-syncs))))
    (testing "before the manifest is written, whose directory is forced last"
      (is (every? #(< % manifest-fill) generation-syncs))
      (is (= [:sync run-dir] (last @events))))))

(defn- ^{:stratum 0} verify-write-before-delete [root]
  (fs/with-temp-dir [events-dir {:prefix "generation-events-"}]
    (let [ctx (fixture/plan-completed-context root {})
          id (:execution/id ctx)
          old (- (System/currentTimeMillis) (* 90 24 60 60 1000))
          delete-run! @#'gc/delete-run!
          _ (store/persist-execution-state! ctx)
          _ (doseq [f (reverse (file-seq (fs/file root (str id))))]
              (.setLastModified ^java.io.File f old))
          ;; The GC has classified the run as expired; a write lands first.
          result (with-redefs-fn {#'gc/delete-run! (fn [& args]
                                                     (store/persist-execution-state! ctx)
                                                     (apply delete-run! args))}
                   #(workflow/gc-checkpoints! {:checkpoint/root root :events-dir (fs/file events-dir)}))]
      (is (= [id] (get-in result [:kept :recent])))
      (is (map? (workflow/load-checkpoint-data id {:checkpoint/root root}))))))

(defn- ^{:stratum 0} verify-pending-interrupt [root]
  (let [written (do (.interrupt (Thread/currentThread))
                    (store/persist-execution-state! (fixture/plan-completed-context root {})))
        ;; Read before `is`, which clears a pending interrupt.
        restored? (Thread/interrupted)]
    (is (not (anomaly/anomaly? written)) "a pending interrupt does not fail the write")
    (is restored? "and is still pending after it")))

(defn- ^{:stratum 0} verify-spec-path-waits-for-the-lock [root]
  (let [ctx (fixture/plan-completed-context root {})
        id (:execution/id ctx)
        entered (promise)
        release (promise)]
    (store/persist-execution-state! ctx)
    (future (checkpoint-lock/with-run-lock root id (fn [] (deliver entered true) @release)))
    (deref entered 5000 nil)
    (let [recording (future (store/record-spec-path! id "/specs/done/spec.md" {:checkpoint/root root}))]
      (is (= ::waiting (deref recording 200 ::waiting)))
      (deliver release true)
      (is (string? (deref recording 5000 nil))))))

(defn- ^{:stratum 0} verify-unsynced-run-dir-prunes-nothing [root]
  (let [ctx (fixture/plan-completed-context root {})
        writes (mapv #(store/persist-execution-state! (assoc ctx :execution/redirect-count %)) (range 2))
        run-dir (str (fs/parent (:checkpoint/manifest-path (last writes))))
        generations (fs/path run-dir "generations")
        before (set (map str (fs/list-dir generations)))
        sync! checkpoint-file/sync-directory!
        failed (with-redefs [checkpoint-file/sync-directory!
                             (fn [dir] (if (= run-dir (str dir)) (throw (IOException. "no fsync")) (sync! dir)))]
                 (store/persist-execution-state! (assoc ctx :execution/redirect-count 2)))]
    (is (anomaly/anomaly? failed))
    (is (= before (set (map str (fs/list-dir generations)))) "nothing pruned, nothing new")))

(defn- ^{:stratum 0} verify-lock-wait-interrupt-kept [root]
  (let [written (with-redefs [checkpoint-lock/with-run-lock
                              (fn [& _] (anomaly/exception-anomaly :unavailable "interrupted while waiting"
                                                                   (InterruptedException.)))]
                  (store/persist-execution-state! (fixture/plan-completed-context root {})))
        interrupted? (Thread/interrupted)]
    (is (anomaly/anomaly? written))
    (is interrupted? "the interrupt the lock wait consumed is set again")))

(deftest ^{:stratum 0} recording-the-spec-path-holds-off-a-pending-interrupt
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [ctx (fixture/plan-completed-context root {})
           _ (store/persist-execution-state! ctx)
           recorded (do (.interrupt (Thread/currentThread))
                        (try (store/record-spec-path! (:execution/id ctx) "/specs/done/spec.md"
                                                      {:checkpoint/root root})
                             (catch InterruptedException e e)))
           restored? (Thread/interrupted)]
       (is (string? recorded) "the lock wait does not fail on it")
       (is restored? "and it is still pending after")))))

(deftest ^{:stratum 0} recording-the-spec-path-keeps-the-root-it-locked
  ;; The root's alias is retargeted while the recorder waits for the lock:
  ;; the manifest it updates is under the root it locked.
  (fixture/call-with-posix-checkpoint-root
   (fn [root]
     (let [first-root (fs/create-dirs (fs/path root "first"))
           second-root (fs/create-dirs (fs/path root "second"))
           alias (fs/create-sym-link (fs/path root "alias") first-root)
           ctx (fixture/plan-completed-context (str first-root) {})
           id (:execution/id ctx)
           _ (store/persist-execution-state! ctx)
           _ (fs/copy-tree (fs/path first-root (str id)) (fs/path second-root (str id)))
           entered (promise)
           release (promise)
           locking (promise)
           with-run-lock checkpoint-lock/with-run-lock
           spec-of #(:workflow/spec-path (edn/read-string (slurp (paths/manifest-path % id))))]
       (future (with-run-lock (str (fs/real-path first-root)) id
                 (fn [] (deliver entered true) @release)))
       (deref entered 5000 nil)
       ;; The alias is retargeted once the recorder has resolved its root and
       ;; asked for the lock, which the holder above keeps it waiting for.
       (with-redefs [checkpoint-lock/with-run-lock (fn [lock-root run-id f]
                                                     (deliver locking lock-root)
                                                     (with-run-lock lock-root run-id f))]
         (let [recording (future (store/record-spec-path! id "/specs/done/spec.md" {:checkpoint/root (str alias)}))]
           (is (= (str (fs/real-path first-root)) (deref locking 5000 nil)))
           (fs/delete alias)
           (fs/create-sym-link alias second-root)
           (deliver release true)
           (deref recording 5000 nil)))
       (is (= "/specs/done/spec.md" (spec-of (str first-root))))
       (is (not= "/specs/done/spec.md" (spec-of (str second-root))))))))

(deftest ^{:stratum 0} recording-the-spec-path-checks-the-root-as-given
  ;; An alias in a directory others can write is refused, as a checkpoint
  ;; write refuses it, though the directory it points to is private.
  (fixture/call-with-posix-checkpoint-root
   (fn [root]
     (let [target (fs/create-dirs (fs/path root "private"))
           shared (fs/create-dirs (fs/path root "shared"))
           alias (fs/create-sym-link (fs/path shared "alias") target)
           ctx (fixture/plan-completed-context (str target) {})
           id (:execution/id ctx)]
       (store/persist-execution-state! ctx)
       (fs/set-posix-file-permissions shared "rwxrwxrwx")
       (is (anomaly/anomaly? (store/record-spec-path! id "/specs/done/spec.md" {:checkpoint/root (str alias)})))
       (is (not= "/specs/done/spec.md"
                 (:workflow/spec-path (edn/read-string (slurp (paths/manifest-path (str target) id))))))))))

(deftest ^{:stratum 0} recording-the-spec-path-reports-lock-failures
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [ctx (fixture/plan-completed-context root {})
           id (:execution/id ctx)
           record! #(store/record-spec-path! id "/specs/done/spec.md" {:checkpoint/root root})]
       (store/persist-execution-state! ctx)
       (testing "a lock that stays busy is an anomaly, not a silent skip"
         (with-redefs [checkpoint-lock/with-run-lock (fn [& _] (anomaly/anomaly :unavailable "busy" {}))]
           (is (anomaly/anomaly? (record!)))))
       (testing "an interrupt the lock wait consumed is set again"
         (let [recorded (with-redefs [checkpoint-lock/with-run-lock
                                      (fn [& _] (anomaly/exception-anomaly :unavailable "interrupted"
                                                                           (InterruptedException.)))]
                          (record!))
               interrupted? (Thread/interrupted)]
           (is (anomaly/anomaly? recorded))
           (is interrupted?)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-crash-before-manifest [root]
  (let [ctx (fixture/plan-completed-context root {})
        id (:execution/id ctx)
        opts {:checkpoint/root root}
        _ (store/persist-execution-state! ctx)
        before (workflow/load-checkpoint-data id opts)
        crashed (with-redefs [checkpoint-file/write! (failing-manifest-write checkpoint-file/write!)]
                  (store/persist-execution-state!
                   (-> ctx
                       (assoc :execution/redirect-count 1)
                       (assoc-in [:execution/phase-results :plan] {:status :failed}))))]
    (is (anomaly/anomaly? crashed))
    (is (= before (workflow/load-checkpoint-data id opts))
        "the previous snapshot and phase checkpoint load unchanged")))

(defn- ^{:stratum 1} verify-pruning [root]
  (let [ctx (fixture/plan-completed-context root {})
        writes (mapv #(store/persist-execution-state! (assoc ctx :execution/redirect-count %)) (range 3))
        generations (fs/parent (fs/parent (:checkpoint/machine-snapshot-path (last writes))))]
    (is (= (set (map (comp generation-of :checkpoint/machine-snapshot-path) (rest writes)))
           (set (map (comp str fs/file-name) (fs/list-dir generations))))
        "the generations of the current and the replaced manifest remain")))

(defn- ^{:stratum 1} verify-flat-layout-run [root]
  (let [ctx (fixture/plan-completed-context root {})
        id (:execution/id ctx)
        manifest-path (paths/manifest-path root id)
        legacy (paths/phase-checkpoint-path root id :plan)
        _ (store/persist-execution-state! ctx)
        ;; As a run written before generations left it.
        _ (fs/create-dirs (fs/parent legacy))
        _ (fs/copy (:plan (fixture/recorded-paths root id)) legacy)
        _ (spit manifest-path (pr-str (assoc-in (edn/read-string (slurp manifest-path))
                                                [:workflow/phase-checkpoints :plan] legacy)))
        written (store/persist-execution-state! (implement-completed ctx))
        loaded (workflow/load-checkpoint-data id {:checkpoint/root root})]
    (is (not (anomaly/anomaly? written)))
    (is (= legacy (:plan (fixture/recorded-paths root id))))
    (is (fs/exists? legacy) "a file outside the generations is never pruned")
    (is (= #{:plan :implement} (set (keys (:phase-results loaded)))))))

(deftest ^{:stratum 1} every-file-is-forced-before-the-manifest-names-it
  (fixture/call-with-temp-checkpoint-root verify-forced-before-manifest))

(defn- ^{:stratum 1} verify-failed-phase-recovered [root]
  (let [planned (fixture/plan-completed-context root {})
        id (:execution/id planned)
        implemented (implement-completed planned)]
    (store/persist-execution-state! planned)
    (with-redefs [checkpoint-file/write! (failing-manifest-write checkpoint-file/write!)]
      (store/persist-execution-state! implemented))
    (store/persist-execution-state! (-> implemented
                                        (assoc :execution/current-phase :verify)
                                        (assoc-in [:execution/phase-results :verify] {:status :completed})))
    (is (= {:status :completed}
           (get-in (workflow/load-checkpoint-data id {:checkpoint/root root}) [:phase-results :implement]))
        "the next write writes the phase the failed one lost")
    (testing "a re-entered phase whose write failed is not left at its older result"
      (let [replanned (-> implemented
                          (assoc :execution/current-phase :plan)
                          (assoc-in [:execution/phase-results :plan] {:status :completed :attempt 2}))]
        (with-redefs [checkpoint-file/write! (failing-manifest-write checkpoint-file/write!)]
          (store/persist-execution-state! replanned))
        (store/persist-execution-state! (assoc replanned :execution/current-phase :implement))
        (is (= {:status :completed :attempt 2}
               (get-in (workflow/load-checkpoint-data id {:checkpoint/root root}) [:phase-results :plan])))))))

(deftest ^{:stratum 1} a-write-between-gc-classification-and-delete-keeps-the-run
  (fixture/call-with-temp-checkpoint-root verify-write-before-delete))

(deftest ^{:stratum 1} a-pending-interrupt-is-held-off-and-restored
  (fixture/call-with-temp-checkpoint-root verify-pending-interrupt))

(deftest ^{:stratum 1} recording-the-spec-path-waits-for-the-run-lock
  (fixture/call-with-temp-checkpoint-root verify-spec-path-waits-for-the-lock))

(defn- ^{:stratum 1} verify-unloadable-phase-rewritten [root]
  (let [planned (fixture/plan-completed-context root {})
        id (:execution/id planned)
        _ (store/persist-execution-state! planned)
        plan-path (:plan (fixture/recorded-paths root id))]
    ;; Same result, but a format this build will not load.
    (spit plan-path (pr-str (assoc (edn/read-string (slurp plan-path)) :checkpoint/format-version 999)))
    (store/persist-execution-state! (implement-completed planned))
    (let [loaded (workflow/load-checkpoint-data id {:checkpoint/root root})]
      (is (= {:status :completed} (get-in loaded [:phase-results :plan])))
      (is (empty? (:checkpoint/read-failures loaded))))))

(deftest ^{:stratum 1} an-unforced-run-directory-stops-the-write-before-pruning
  (fixture/call-with-temp-checkpoint-root verify-unsynced-run-dir-prunes-nothing))

(deftest ^{:stratum 1} an-interrupt-the-lock-wait-consumed-is-kept
  (fixture/call-with-temp-checkpoint-root verify-lock-wait-interrupt-kept))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-write-that-dies-before-its-manifest-leaves-the-previous-checkpoint
  (fixture/call-with-temp-checkpoint-root verify-crash-before-manifest))

(deftest ^{:stratum 2} only-the-current-and-previous-generations-are-kept
  (fixture/call-with-temp-checkpoint-root verify-pruning))

(deftest ^{:stratum 2} a-phase-a-failed-write-lost-is-written-by-the-next
  (fixture/call-with-temp-checkpoint-root verify-failed-phase-recovered))

(deftest ^{:stratum 2} a-phase-file-the-loader-rejects-is-rewritten
  (fixture/call-with-temp-checkpoint-root verify-unloadable-phase-rewritten))

(deftest ^{:stratum 2} a-run-written-in-the-flat-layout-keeps-its-files
  (fixture/call-with-temp-checkpoint-root verify-flat-layout-run))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.checkpoint-generation-test))
