;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-journal-test-support
  (:require [ai.miniforge.event-stream.commit-test-support :as support]
            [ai.miniforge.event-stream.boundary.journal-files :as files]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} timeout-ms 10000)

(def ^{:private true :stratum 0} lock-probe
  "(with-open [c (java.nio.channels.FileChannel/open (.toPath (clojure.java.io/file (first *command-line-args*))) (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/WRITE]))] (println (boolean (.tryLock c))))")

(defn ^{:stratum 0} with-directory [f]
  (let [root (.getCanonicalFile (.toFile (Files/createTempDirectory "event-journal-"
                                                                 (make-array FileAttribute 0))))]
    (try (f (.getPath root))
         (finally (doseq [file (reverse (file-seq root))] (io/delete-file file))))))

(defn ^{:stratum 0} draft []
  (assoc (support/draft) :data {:instant (Instant/parse "2026-10-02T00:00:00.123456789Z")
                               :list '(one two)
                               :set #{:a :b}}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} child-lock [directory]
  (let [path (.getPath (io/file directory files/lock-filename))
        result (shell/sh "bb" "-e" lock-probe path)]
    (is (zero? (:exit result)) (:err result))
    (:out result)))

(defn ^{:stratum 1} block-publication [entered resume publish! directory value]
  (deliver entered true)
  (deref resume timeout-ms nil)
  (publish! directory value))

(comment
  (draft))
