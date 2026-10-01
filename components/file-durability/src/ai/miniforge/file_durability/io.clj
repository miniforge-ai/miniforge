;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.io
  "Java filesystem primitives; public calls convert failures at the boundary."
  (:import [java.io File]
           [java.nio ByteBuffer CharBuffer]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file LinkOption OpenOption StandardOpenOption]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} write-buffer! [^File file ^ByteBuffer buffer options]
  (with-open [channel (FileChannel/open (.toPath file) (into-array OpenOption options))]
    (while (.hasRemaining buffer) (.write channel buffer))
    (.force channel true)))

(defn- ^{:stratum 0} sync-directory! [^File directory]
  (with-open [channel (FileChannel/open (.toPath directory)
                                      (into-array OpenOption [StandardOpenOption/READ]))]
    (.force channel true)))

(defn- ^{:stratum 0} sync-file! [^File file]
  (with-open [channel (FileChannel/open (.toPath file)
                      (into-array OpenOption [StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS]))]
    (.force channel true)))

(defn- ^{:stratum 0} parent-file [^File file]
  (.getParentFile file))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} write-new-text! [file ^String encoded]
  (let [buffer (.encode (.newEncoder StandardCharsets/UTF_8) (CharBuffer/wrap encoded))]
    (write-buffer! file buffer [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE])))

(defn ^{:stratum 1} write-temporary-bytes! [file bytes]
  (write-buffer! file (ByteBuffer/wrap bytes)
                 [StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS]))

(defn ^{:stratum 1} sync-ancestry! [^File directory]
  (doseq [parent (take-while some? (iterate parent-file (.getAbsoluteFile directory)))]
    (sync-directory! parent)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} confirm! [^File file]
  (sync-file! file)
  (sync-ancestry! (.getParentFile (.getAbsoluteFile file))))

(comment
  (parent-file (File. "/tmp/example")))
