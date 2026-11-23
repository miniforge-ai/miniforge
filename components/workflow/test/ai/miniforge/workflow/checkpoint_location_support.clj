;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-location-support
  (:require [ai.miniforge.event-stream.interface :as events]
            [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private archived-filename "0000000000000001__000000000000.transit.json")

(defn- ^{:stratum 0} record [root directory path events-directory kind]
  {:root (str root)
   :directory (str directory)
   :path (str path)
   :events events-directory
   :kind kind})

(defn- ^{:stratum 0} write-file! [path]
  (spit (fs/file path) "{}")
  path)

(defn ^{:stratum 0} announcement
  ([layout id]
   (announcement layout id (events/checkpoint-location (:root layout) (:directory layout))))
  ([layout id locator]
   (let [stream (events/create-event-stream {:sinks []})
         path (:path layout)
         size (fs/size path)]
     (case (:kind layout)
       :phase (events/checkpoint-written stream id :plan path size locator)
       :snapshot (events/machine-snapshot-written stream id :running path size locator)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} phase-layout! [root id]
  (let [checkpoint-root (fs/create-dir (fs/path root "alternate"))
        directory (fs/create-dir (fs/path checkpoint-root (str id)))
        phases (fs/create-dir (fs/path root "outside-phases"))
        _alias (fs/create-sym-link (fs/path directory "phases") phases)
        _manifest (write-file! (fs/path directory "manifest.edn"))
        path (write-file! (fs/path phases "plan.edn"))]
    (record checkpoint-root directory path (fs/path root "events") :phase)))

(defn- ^{:stratum 1} snapshot-layout! [root id]
  (let [checkpoint-root (fs/create-dir (fs/path root "alternate"))
        directory (fs/create-dir (fs/path root "physical-run"))
        _alias (fs/create-sym-link (fs/path checkpoint-root (str id)) directory)
        path (write-file! (fs/path directory "machine.edn"))]
    (record checkpoint-root directory path (fs/path root "events") :snapshot)))

(defn ^{:stratum 1} archive! [layout id event]
  (let [directory (fs/create-dirs (fs/path (:events layout) "archived" (str id)))
        file (fs/path directory archived-filename)]
    (spit (fs/file file) (events/serialize-event event))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} layout! [root id kind]
  (case kind
    :phase (phase-layout! (fs/real-path root) id)
    :snapshot (snapshot-layout! (fs/real-path root) id)))

(defn ^{:stratum 2} announce! [layout id]
  (archive! layout id (announcement layout id)))

(comment ::aliased-retained-checkpoint-fixtures)
