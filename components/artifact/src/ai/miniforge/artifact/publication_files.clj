;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-files
  "Filesystem primitives for complete, create-only artifact records."
  (:require [ai.miniforge.artifact.publication-codec :as codec]
            [clojure.java.io :as io])
  (:import [java.io File]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.file Files LinkOption OpenOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:private true :stratum 0} no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn ^{:stratum 0} target ^File [directory id]
  (io/file directory (str id ".artifact.transit.json")))

(defn- ^{:stratum 0} sync-directory! [^File directory]
  (with-open [channel (FileChannel/open (.toPath directory)
                                      (into-array OpenOption [StandardOpenOption/READ]))]
    (.force channel true)))

(defn ^{:stratum 0} write! [^File file bytes]
  (with-open [channel (FileChannel/open (.toPath file)
                      (into-array OpenOption [StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS]))]
    (let [buffer (ByteBuffer/wrap bytes)]
      (while (.hasRemaining buffer) (.write channel buffer))
      (.force channel true))))

(defn ^{:stratum 0} temporary ^File [^File directory]
  (.toFile (Files/createTempFile (.toPath directory) ".artifact-" ".tmp" (make-array FileAttribute 0))))

(defn ^{:stratum 0} publish-link! [^File file ^File temporary]
  (Files/createLink (.toPath file) (.toPath temporary)))

(defn ^{:stratum 0} read-bytes [^File file]
  (with-open [channel (FileChannel/open (.toPath file)
                      (into-array OpenOption [StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS]))]
    (let [size (.size channel)]
      (when (<= 0 size codec/maximum-bytes)
        (let [buffer (ByteBuffer/allocate (int size))]
          (loop []
            (cond
              (not (.hasRemaining buffer)) (.array buffer)
              (neg? (.read channel buffer)) nil
              :else (recur))))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} safe-directory? [directory]
  (let [file (io/file directory)]
    (and (.isAbsolute file)
         (= (.getAbsolutePath file) (.getCanonicalPath file))
         (Files/isDirectory (.toPath file) no-follow))))

(defn ^{:stratum 1} absent? [^File file]
  (Files/notExists (.toPath file) no-follow))

(defn ^{:stratum 1} regular? [^File file]
  (Files/isRegularFile (.toPath file) no-follow))

(defn ^{:stratum 1} confirm! [^File file]
  (with-open [channel (FileChannel/open (.toPath file)
                      (into-array OpenOption [StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS]))]
    (.force channel true))
  (doseq [directory (take-while some? (iterate #(.getParentFile ^File %) (.getParentFile file)))]
    (sync-directory! directory)))

(comment
  (safe-directory? "/tmp"))
