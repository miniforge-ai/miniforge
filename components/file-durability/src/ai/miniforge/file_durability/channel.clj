;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.channel
  "Own writable channels through complete buffer writes and force operations."
  (:import [java.io File]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.file OpenOption]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} open! [^File file options]
  (FileChannel/open (.toPath file) (into-array OpenOption options)))

(defn ^{:stratum 0} force! [^FileChannel channel] (.force channel true))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} fill-and-force! [^FileChannel channel ^ByteBuffer buffer]
  (while (.hasRemaining buffer) (.write channel buffer))
  (force! channel))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} write-buffer! [file buffer options]
  (with-open [channel (open! file options)]
    (fill-and-force! channel buffer)))

(comment ::owned-file-channel)
