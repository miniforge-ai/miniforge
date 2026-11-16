;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.run-transition-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.run-authority-fixtures :as fixture]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} child-expression
  "A separate process owns the exact transition lock until its stdin closes."
  "(with-open [channel (java.nio.channels.FileChannel/open
                         (.toPath (clojure.java.io/file (first *command-line-args*)))
                         (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/WRITE]))
                held (.lock channel)]
     (println :locked) (flush) (read-line))")

(defn- ^{:stratum 0} read-ready [child]
  (with-open [reader (io/reader (.getInputStream child))] (.readLine reader)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-process-lock [root]
  (let [id (random-uuid)
        _ (grant/register-run-authority! root id fixture/now)
        file (str (io/file root "runs" (str id ".lock")))
        child (.start (ProcessBuilder. ["bb" "-e" child-expression file]))
        ready (future (read-ready child))]
    (try
      (is (= ":locked" (deref ready fixture/wait-ms :timeout)))
      (is (anomaly/anomaly? (grant/full-stop-run-authority! root (fixture/stop-request id))))
      (finally
        (.destroyForcibly child)
        (is (.waitFor child fixture/wait-ms java.util.concurrent.TimeUnit/MILLISECONDS))
        (future-cancel ready)))
    (is (= :full-stop (:authority/state (grant/full-stop-run-authority! root (fixture/stop-request id)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} another-process-lock-refuses-stop-until-released
  (fixture/call-with-root verify-process-lock))
