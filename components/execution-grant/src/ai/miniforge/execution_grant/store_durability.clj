;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store-durability
  "Filesystem primitives; the store I/O boundary converts their failures to data."
  (:import [java.io File]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file LinkOption OpenOption StandardOpenOption]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} write!
  [^File file ^String encoded]
  (let [options (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE])
        bytes (ByteBuffer/wrap (.getBytes encoded StandardCharsets/UTF_8))]
    (with-open [channel (FileChannel/open (.toPath file) options)]
      (while (.hasRemaining bytes) (.write channel bytes))
      (.force channel true))))

(defn- ^{:stratum 0} parent-file
  [^File file]
  (.getParentFile file))

(defn- ^{:stratum 0} sync-directory!
  [^File directory]
  (with-open [channel (FileChannel/open (.toPath directory)
                                      (into-array OpenOption [StandardOpenOption/READ]))]
    (.force channel true)))

(defn- ^{:stratum 0} sync-file!
  [^File file]
  (let [options (into-array OpenOption [StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS])]
    (with-open [channel (FileChannel/open (.toPath file) options)]
      (.force channel true))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} sync-ancestry!
  "Flush publication and any newly created ancestor directory entries."
  [^File directory]
  (doseq [parent (take-while some? (iterate parent-file (.getAbsoluteFile directory)))]
    (sync-directory! parent)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} confirm!
  "Retry durability barriers before acknowledging an existing revocation."
  [^File file]
  (sync-file! file)
  (sync-ancestry! (.getParentFile file)))

(comment
  (parent-file (File. "/tmp/example")))
