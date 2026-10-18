;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.journal-files
  "Filesystem primitives beneath the journal owner registry."
  (:require [ai.miniforge.event-stream.commit-model :as model]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]])
  (:import [java.nio.channels FileChannel]
           [java.nio.file Files LinkOption OpenOption StandardOpenOption]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} lock-filename ".event-journal.lock")

(def ^{:private true :stratum 0} no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} safe-directory? [directory]
  (and (string? directory) (not (str/blank? directory))
       (let [file (io/file directory)]
         (and (.isAbsolute file)
              (= directory (.getCanonicalPath file))
              (Files/isDirectory (.toPath file) no-follow)))))

(defn ^{:stratum 1} lock-channel! [directory]
  (let [path (.toPath (io/file directory lock-filename))
        options (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE
                                        LinkOption/NOFOLLOW_LINKS])
        channel (FileChannel/open path options)
        acquired? (atom false)]
    (try+
      (reset! acquired? (some? (.tryLock channel)))
      (if @acquired? channel (model/failure :conflict :journal/ownership nil))
      ;; Closing a dedicated channel releases the lock on JVM and Babashka.
      ;; Never unlink the lock file: another process could lock a different inode.
      (finally (when-not @acquired? (.close channel))))))

(comment
  ::journal-lock-file)
