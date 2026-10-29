;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.retention-hold-test
  "Event cleanup keeps an expired workflow directory that a registered
   `retention-hold` still needs."
  (:require [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.event-stream.sinks :as sinks]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private held-ids
  "Workflow ids the test hold keeps; `::throws` makes it throw."
  (atom #{}))

(defn- ^{:stratum 0} aged-file! [f days]
  (io/make-parents f)
  (spit f "{}")
  (.setLastModified ^java.io.File f (- (System/currentTimeMillis) (* days 24 60 60 1000)))
  f)

(defn- ^{:stratum 0} with-events-dir [f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "retention-hold-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (f dir)
         (finally (doseq [file (reverse (file-seq dir))] (.delete ^java.io.File file))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} with-test-hold
  "Register a hold answering from `held-ids` while `f` runs."
  [f]
  (.addMethod ^clojure.lang.MultiFn es/retention-hold ::test
              (fn [_hold workflow-id _events-dir]
                (when (contains? @held-ids ::throws)
                  (throw (ex-info "hold unavailable" {})))
                (contains? @held-ids workflow-id)))
  (try (f)
       (finally (remove-method es/retention-hold ::test)
                (reset! held-ids #{}))))

(defn- ^{:stratum 1} expired-archive!
  "An archived workflow directory whose archive is 8 days old."
  [events-dir workflow-id]
  (let [dir (io/file events-dir "archived" workflow-id)]
    (aged-file! (io/file dir "0000000000000001__000000000000.transit.json") 8)
    (aged-file! (io/file dir "archived.marker") 8)
    dir))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-held-archive-outlives-its-ttl
  (with-test-hold
    (fn []
      (with-events-dir
        (fn [events-dir]
          (let [held (expired-archive! events-dir "wf-held")
                free (expired-archive! events-dir "wf-free")]
            (reset! held-ids #{"wf-held"})
            (sinks/cleanup-stale-events! {:events-dir events-dir})
            (testing "the held workflow keeps its events"
              (is (.isDirectory held)))
            (testing "an unheld one is pruned as before"
              (is (not (.exists free))))))))))

(deftest ^{:stratum 2} a-hold-that-throws-keeps-the-archive
  (with-test-hold
    (fn []
      (with-events-dir
        (fn [events-dir]
          (let [archive (expired-archive! events-dir "wf-unknown")]
            (reset! held-ids #{::throws})
            (sinks/cleanup-stale-events! {:events-dir events-dir})
            (is (.isDirectory archive))))))))

(deftest ^{:stratum 2} a-held-legacy-directory-outlives-its-ttl
  (with-test-hold
    (fn []
      (with-events-dir
        (fn [events-dir]
          (let [dir (io/file events-dir "wf-legacy")]
            (aged-file! (io/file dir "20200101T000000Z-abc.json") 30)
            (.setLastModified dir (- (System/currentTimeMillis) (* 30 24 60 60 1000)))
            (reset! held-ids #{"wf-legacy"})
            (sinks/cleanup-stale-events! {:events-dir events-dir})
            (is (.isDirectory dir))))))))

(comment ::retention-hold)
