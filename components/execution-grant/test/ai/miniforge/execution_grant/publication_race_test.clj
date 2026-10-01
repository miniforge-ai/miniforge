;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.publication-race-test
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.store-read :as reader]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files NoSuchFileException]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} miss-once [read-text calls file]
  (if (= 1 (swap! calls inc))
    (throw (NoSuchFileException. (.getPath file)))
    (read-text file)))

(defn- ^{:stratum 0} missing-open [calls file]
  (swap! calls inc)
  (throw (NoSuchFileException. (.getPath file))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} missing-open-followed-by-publication-is-reread-test
  (let [dir (.toFile (Files/createTempDirectory "grant-publication-race" (make-array FileAttribute 0)))
        at (java.time.Instant/parse "2026-09-30T00:00:00Z")
        g (grant/issue {:principal "workflow:test" :effect-class :effect/pr-create
                        :scope {:effect/id (random-uuid)} :constraints {:constraint/max-count 1}
                        :delegable? false :expires-at (.plusSeconds at 60)} at)
        calls (atom 0)]
    (try
      (is (= g (grant/register! (.getCanonicalPath dir) g)))
      ;; The syscall saw absence; by the catch's existence check the file is published.
      (with-redefs-fn {#'reader/read-text (partial miss-once @#'reader/read-text calls)}
        #(is (= g (grant/current (.getCanonicalPath dir) (:grant/id g)))))
      (is (= 3 @calls))
      (reset! calls 0)
      (with-redefs-fn {#'reader/read-text (partial missing-open calls)}
        #(is (= :fault (:anomaly/type (grant/current (.getCanonicalPath dir) (:grant/id g))))))
      (is (= 3 @calls) "Two attempts for the published grant; one for its absent revocation")
      (is (= g (grant/current (.getCanonicalPath dir) (:grant/id g))))
      (finally (doseq [file (reverse (file-seq dir))] (io/delete-file file))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.execution-grant.publication-race-test))
