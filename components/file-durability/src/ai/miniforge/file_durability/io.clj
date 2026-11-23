;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.io
  "Java filesystem primitives; public calls convert failures at the boundary."
  (:require [ai.miniforge.file-durability.channel :as channel])
  (:import [java.io File]
           [java.nio ByteBuffer CharBuffer]
           [java.nio.charset StandardCharsets]
           [java.nio.file LinkOption StandardOpenOption]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} sync-directory! [^File directory]
  (with-open [channel (channel/open! directory [StandardOpenOption/READ])]
    (channel/force! channel)))

(defn- ^{:stratum 0} sync-file! [^File file]
  (with-open [channel (channel/open! file [StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS])]
    (channel/force! channel)))

(defn- ^{:stratum 0} parent-file [^File file]
  (.getParentFile file))

(defn ^{:stratum 0} write-new-text! [file ^String encoded]
  (let [buffer (.encode (.newEncoder StandardCharsets/UTF_8) (CharBuffer/wrap encoded))]
    (channel/write-buffer! file buffer [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE])))

(defn ^{:stratum 0} write-temporary-bytes! [file bytes]
  (channel/write-buffer! file (ByteBuffer/wrap bytes)
                 [StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} sync-ancestry! [^File directory]
  (doseq [parent (take-while some? (iterate parent-file (.getAbsoluteFile directory)))]
    (sync-directory! parent)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish-bytes! [file bytes publish!]
  (with-open [channel (channel/open! file [StandardOpenOption/WRITE StandardOpenOption/TRUNCATE_EXISTING LinkOption/NOFOLLOW_LINKS])]
    (channel/fill-and-force! channel (ByteBuffer/wrap bytes))
    (let [^File published (publish!)]
      (channel/force! channel)
      (sync-ancestry! (.getParentFile (.getAbsoluteFile published)))
      published)))

(defn ^{:stratum 2} confirm! [^File file]
  (sync-file! file)
  (sync-ancestry! (.getParentFile (.getAbsoluteFile file))))

(comment
  (parent-file (File. "/tmp/example")))
