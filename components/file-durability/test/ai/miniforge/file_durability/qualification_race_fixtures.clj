;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.qualification-race-fixtures
  "Disposable path replacement while the real native inode pin stays owned."
  (:require [ai.miniforge.file-durability.boundary.directory-lease :as lease]
            [ai.miniforge.file-durability.boundary.directory-pin :as pin]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.qualification-fixtures :as qualification]
            [babashka.fs :as fs])
  (:import [java.nio.file Files]
           [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record-pin! [held open! root limit until]
  (let [actual (open! root limit until)]
    (vreset! held actual)
    actual))

(defn ^{:stratum 0} replace! [root]
  (Files/delete (fs/path root))
  (durability/prepare-protected-directory! (fs/file root)))

(defn ^{:stratum 0} close-before-confirmation! [held _root _peer]
  (lease/release! @held)
  true)

(defn ^{:stratum 0} fail-proof! [_held _root _peer] false)

(defn ^{:stratum 0} throw-proof! [_held _root _peer] (throw (IOException.)))

(defn ^{:stratum 0} reject-io! [& _] (throw (AssertionError.)))

(defn ^{:stratum 0} count-identity! [reads original _path]
  (swap! reads inc)
  original)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} replace-observation! [held changed profile root]
  (when (and @held (compare-and-set! changed false true)) (replace! root))
  (qualification/candidate profile root))

(defn ^{:stratum 1} with-pin! [held operation]
  (with-redefs [pin/open! (partial record-pin! held pin/open!)] (operation)))

(comment (deref (atom false)))
