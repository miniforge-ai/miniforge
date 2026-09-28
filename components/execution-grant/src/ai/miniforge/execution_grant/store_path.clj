;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store-path
  "Reject linked filesystem components before authority I/O."
  (:import [java.io File]
           [java.nio.file Files]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} parent [^File file] (.getParentFile file))

(defn- ^{:stratum 0} linked? [^File file] (Files/isSymbolicLink (.toPath file)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} safe?
  [^File file]
  (not-any? linked? (take-while some? (iterate parent (.getAbsoluteFile file)))))

(comment
  (safe? (File. "/private/tmp/example")))
