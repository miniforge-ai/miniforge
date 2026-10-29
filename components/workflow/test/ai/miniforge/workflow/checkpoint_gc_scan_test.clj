;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-gc-scan-test
  "The checkpoint GC's scan of a run directory while files in it are
   renamed: a checkpoint or pin write landing mid-scan must not fail it,
   and a pin marker's temporary file is not a change to the run."
  (:require [ai.miniforge.workflow.checkpoint-store-paths :as paths]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private ninety-days-ago-ms
  (- (System/currentTimeMillis) (* 90 24 60 60 1000)))

(defn- ^{:stratum 0} gc!
  [root events-dir]
  (workflow/gc-checkpoints! {:checkpoint/root root :events-dir (fs/file events-dir)}))

(deftest ^{:stratum 0} pin-writes-its-marker-through-a-temp-file-the-gc-skips
  (fs/with-temp-dir [root {:prefix "gc-scan-"}]
    (let [root (str root)
          id (fixture/expired-run! root)
          written (atom [])
          write spit]
      (with-redefs [spit (fn [f & args]
                           (swap! written conj (str f))
                           (apply write f args))]
        (is (string? (workflow/pin-checkpoint! id {:checkpoint/root root}))))
      (is (= 1 (count @written)))
      (is (paths/pin-marker-temp? (first @written)))
      (is (= [] (filterv paths/pin-marker-temp? (fs/list-dir (fs/file root (str id)))))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} age!
  "Set `files`' mtimes 90 days back, the run directory's last."
  [& files]
  (doseq [f files]
    (.setLastModified (fs/file f) ninety-days-ago-ms)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-file-renamed-mid-scan-does-not-fail-the-gc
  (fs/with-temp-dir [root {:prefix "gc-scan-"}]
    (fs/with-temp-dir [events-dir {:prefix "gc-scan-events-"}]
      (let [root (str root)
            id (fixture/expired-run! root)
            dir (fs/file root (str id))
            temp (fs/file dir "manifest.edn.tmp")
            walk fs/walk-file-tree]
        (spit temp "{:workflow/id 1}")
        (age! temp dir)
        ;; The walk has listed the temp file; its write renames it onto
        ;; the manifest before the GC's visitor sees it.
        (with-redefs [fs/walk-file-tree
                      (fn [path opts]
                        (walk path (update opts :visit-file
                                           (fn [visit]
                                             (fn [p attrs]
                                               (when (= "manifest.edn.tmp" (str (fs/file-name p)))
                                                 (fs/move p (fs/file dir "manifest.edn")
                                                          {:replace-existing true}))
                                               (visit p attrs))))))]
          (let [result (gc! root events-dir)]
            (is (empty? (:failures result)))
            (is (some #{id} (concat (:deleted result) (mapcat val (:kept result)))))))))))

(deftest ^{:stratum 2} a-pin-marker-temp-file-does-not-keep-a-run-recent
  (fs/with-temp-dir [root {:prefix "gc-scan-"}]
    (fs/with-temp-dir [events-dir {:prefix "gc-scan-events-"}]
      (let [root (str root)
            id (fixture/expired-run! root)
            dir (fs/file root (str id))]
        ;; Left by a pin that stopped before its rename: fresh, not a pin.
        (spit (fs/file dir (str paths/pin-marker-temp-prefix (random-uuid) ".tmp")) "{}")
        (age! dir)
        (is (= [id] (:deleted (gc! root events-dir))))))))

(comment ::gc-scan)
