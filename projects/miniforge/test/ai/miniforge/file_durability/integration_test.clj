;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.interface :as durability]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private contents "durable λ")

(defn- ^{:stratum 0} with-directory [check]
  (let [root (.getCanonicalFile (.toFile (Files/createTempDirectory "file-durability-"
                                                                 (make-array FileAttribute 0))))]
    (try (check root)
         (finally (doseq [file (reverse (file-seq root))] (io/delete-file file))))))

(defn- ^{:stratum 0} check-missing-and-malformed [directory]
  (let [file (io/file directory "missing")
        malformed (str (char 0xd800))]
    (is (anomaly/anomaly? (durability/confirm! file)))
    (is (anomaly/anomaly? (durability/sync-ancestry! file)))
    (is (anomaly/anomaly? (durability/write-temporary-bytes! file (byte-array 0))))
    (is (anomaly/anomaly? (durability/write-new-text! file malformed)))
    (is (not (.exists file)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-create-only [directory]
  (let [file (io/file directory "record")]
    (is (= file (durability/write-new-text! file contents)))
    (is (= contents (slurp file :encoding "UTF-8")))
    (is (anomaly/anomaly? (durability/write-new-text! file "replacement")))
    (is (= contents (slurp file :encoding "UTF-8")))
    (is (= file (durability/confirm! file)))
    (is (= directory (durability/sync-ancestry! directory)))))

(defn- ^{:stratum 1} check-temporary-and-symlink [directory]
  (let [file (.toFile (Files/createTempFile (.toPath directory) "record" ".tmp"
                                          (make-array FileAttribute 0)))
        link (io/file directory "link")
        bytes (.getBytes contents StandardCharsets/UTF_8)]
    (is (= file (durability/write-temporary-bytes! file bytes)))
    (Files/createSymbolicLink (.toPath link) (.toPath file) (make-array FileAttribute 0))
    (is (anomaly/anomaly? (durability/write-temporary-bytes! link bytes)))
    (is (anomaly/anomaly? (durability/write-new-text! link "replacement")))
    (is (anomaly/anomaly? (durability/confirm! link)))
    (is (= contents (slurp file :encoding "UTF-8")))))

(deftest ^{:stratum 1} failed-barriers-and-malformed-unicode-cannot-succeed
  (with-directory check-missing-and-malformed))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} complete-utf8-writes-never-replace-existing-records
  (with-directory check-create-only))

(deftest ^{:stratum 2} temporary-writes-and-confirmation-do-not-follow-final-symlinks
  (with-directory check-temporary-and-symlink))

(comment
  (clojure.test/run-tests 'ai.miniforge.file-durability.integration-test))
