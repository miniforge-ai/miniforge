;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.descriptor-pin-fixtures
  (:require [ai.miniforge.file-durability.boundary.descriptor-process :as process]
            [ai.miniforge.file-durability.boundary.directory-lease :as lease]
            [ai.miniforge.file-durability.boundary.native-directory-pin :as pin]
            [ai.miniforge.file-durability.boundary.native-process-exit :as exit]
            [ai.miniforge.file-durability.boundary.probe-deadline :as deadline]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+]])
  (:import [java.nio.file Files LinkOption]
           [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} timeout-ms
  "Allow cold native interpreter startup while bounding every disposable helper."
  5000)

(def ^{:stratum 0} receipt-timeout-ms
  "A silent injected helper must fail promptly without becoming a long test."
  200)

(def ^{:stratum 0} silent-program
  "Outlive receipt acquisition; only the owned cleanup may end this helper."
  "sleep 60;")

(def ^{:stratum 0} malformed-program
  "A real native process supplies untrusted bytes, not a fabricated Process map."
  "print qq{not an identity\n}; close STDOUT; sleep 60;")

(defn ^{:stratum 0} record-start! [held launch! path]
  (let [child (launch! path)]
    (vreset! held child)
    child))

(defn ^{:stratum 0} launch-program! [program _path]
  (let [builder (ProcessBuilder. ^java.util.List [process/executable "-f" "-e" program])]
    (.clear (.environment builder))
    (.start builder)))

(defn ^{:stratum 0} path-identity [path]
  (let [attributes (Files/readAttributes (fs/path path) "unix:dev,ino" (make-array LinkOption 0))
        device (get attributes "dev")
        inode (get attributes "ino")]
    (identity/value device inode)))

(defn- ^{:stratum 0} release-result! [result]
  (when (satisfies? lease/DirectoryLease result) (lease/release! result)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} exited? [^Process child]
  (.waitFor child timeout-ms TimeUnit/MILLISECONDS))

(defn ^{:stratum 1} acquire! [held launch! path budget-ms]
  (with-redefs [process/start! (partial record-start! held launch!)]
    (pin/open! path (deadline/after-ms budget-ms) timeout-ms)))

(defn- ^{:stratum 1} reap-captured! [held]
  (when-let [child @held] (exit/stop! child timeout-ms)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-helper! [held launch! path budget-ms verify!]
  (let [result (acquire! held launch! path budget-ms)]
    (try+
      (verify! result)
      (finally
        (cleanup/finish! (partial release-result! result) (partial reap-captured! held))))))

(comment (path-identity (fs/path ".")))
