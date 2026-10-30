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

(defn- ^{:stratum 0} verify-pending-interrupt [root]
  (let [written (do (.interrupt (Thread/currentThread))
                    (store/persist-execution-state! (fixture/plan-completed-context root {})))
        ;; Read before `is`, which clears a pending interrupt.
        restored? (Thread/interrupted)]
    (is (not (anomaly/anomaly? written)) "a pending interrupt does not fail the write")
    (is restored? "and is still pending after it")))

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

(deftest ^{:stratum 1} a-pending-interrupt-is-held-off-and-restored
  (fixture/call-with-temp-checkpoint-root verify-pending-interrupt))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-write-that-dies-before-its-manifest-leaves-the-previous-checkpoint
  (fixture/call-with-temp-checkpoint-root verify-crash-before-manifest))

(deftest ^{:stratum 2} only-the-current-and-previous-generations-are-kept
  (fixture/call-with-temp-checkpoint-root verify-pruning))

(deftest ^{:stratum 2} a-phase-a-failed-write-lost-is-written-by-the-next
  (fixture/call-with-temp-checkpoint-root verify-failed-phase-recovered))

(deftest ^{:stratum 2} a-run-written-in-the-flat-layout-keeps-its-files
  (fixture/call-with-temp-checkpoint-root verify-flat-layout-run))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.checkpoint-generation-test))
