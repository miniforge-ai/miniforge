;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-usage-test
  "`checkpoint-usage` summarises the checkpoint root without changing it."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private day-ms (* 24 60 60 1000))

(deftest ^{:stratum 0} an-empty-or-missing-root-has-no-runs
  (fs/with-temp-dir [root {:prefix "checkpoint-usage-"}]
    (is (= {:runs 0 :older 0 :oldest-days nil :max-age-days 30}
           (dissoc (workflow/checkpoint-usage {:checkpoint/root (str root)}) :checkpoint/root)))
    (is (zero? (:runs (workflow/checkpoint-usage {:checkpoint/root (str (fs/path root "absent"))}))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-dir! [root days]
  (let [dir (io/file (str root) (str (random-uuid)))]
    (.mkdirs dir)
    (spit (io/file dir "manifest.edn") "{}")
    (.setLastModified dir (- (System/currentTimeMillis) (* days day-ms)))
    dir))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} usage-counts-runs-and-ages-by-directory
  (fs/with-temp-dir [root {:prefix "checkpoint-usage-"}]
    (run-dir! root 2)
    (run-dir! root 40)
    (run-dir! root 90)
    (doto (io/file (str root) "not-a-run") (.mkdirs)
      (.setLastModified (- (System/currentTimeMillis) (* 400 day-ms))))
    (let [usage (workflow/checkpoint-usage {:checkpoint/root (str root)})]
      (is (= {:runs 3 :older 2 :oldest-days 90 :max-age-days 30}
             (dissoc usage :checkpoint/root)))
      (testing "nothing is deleted"
        (is (= 4 (count (fs/list-dir root))))))))

(deftest ^{:stratum 2} a-run-that-vanishes-mid-read-is-skipped
  ;; A concurrent GC deletes a run between the listing and the mtime read.
  (fs/with-temp-dir [root {:prefix "checkpoint-usage-"}]
    (let [gone (run-dir! root 90)
          _ (run-dir! root 2)
          mtime fs/last-modified-time]
      (with-redefs [fs/last-modified-time (fn [path & args]
                                            (when (= (str path) (str gone))
                                              (throw (java.nio.file.NoSuchFileException. (str path))))
                                            (apply mtime path args))]
        (is (= {:runs 1 :older 0 :oldest-days 2 :max-age-days 30}
               (dissoc (workflow/checkpoint-usage {:checkpoint/root (str root)})
                       :checkpoint/root)))))))

(deftest ^{:stratum 2} an-unreadable-root-is-an-anomaly-naming-it
  (fs/with-temp-dir [root {:prefix "checkpoint-usage-"}]
    (run-dir! root 2)
    (let [list-dir fs/list-dir]
      (with-redefs [fs/list-dir (fn [path & args]
                                  (if (= (str path) (str root))
                                    (throw (java.nio.file.AccessDeniedException. (str path)))
                                    (apply list-dir path args)))]
        (let [usage (workflow/checkpoint-usage {:checkpoint/root (str root)})]
          (is (anomaly/anomaly? usage))
          (is (= (str root) (get-in usage [:anomaly/data :checkpoint/root]))))))))

(comment ::checkpoint-usage)
