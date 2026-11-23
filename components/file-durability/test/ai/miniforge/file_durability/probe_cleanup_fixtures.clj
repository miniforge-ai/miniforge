;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.probe-cleanup-fixtures
  "Actual disposable process records; independent cleanup survives injected faults."
  (:require [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.test :refer [is]]
            [slingshot.slingshot :refer [try+]])
  (:import [java.lang ProcessHandle]
           [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} budget-ms
  "Allow native process scheduling and acknowledgement without long-lived test children."
  3000)

(def ^{:stratum 0} refusal-ms
  "A suppressed native kill cannot consume the fixture's thirty-second lifetime."
  50)

(def ^{:stratum 0} scripts
  "Ready is emitted only after the optional child exists; neither process uses a provider."
  {:single "printf 'ready\n'; exec sleep 30"
   :tree "sleep 30 & printf 'ready\n'; wait"})

(defn ^{:stratum 0} dead? [^ProcessHandle handle] (not (.isAlive handle)))

(defn- ^{:stratum 0} targets [child]
  (let [^Process worker (:proc child)]
    (with-open [descendants (.descendants (.toHandle worker))]
      (conj (vec (reverse (iterator-seq (.iterator descendants)))) (.toHandle worker)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} force-fixture! [handles]
  (doseq [^ProcessHandle handle handles :when (not (dead? handle))]
    (.destroyForcibly handle)
    (.get (.onExit handle) budget-ms TimeUnit/MILLISECONDS)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-native! [mode f]
  (let [child (process/process ["/bin/sh" "-c" (get scripts mode)] {:out :pipe :err :discard})
        ready (future (.readLine ^java.io.BufferedReader (io/reader (:out child))))
        handles (volatile! [(.toHandle ^Process (:proc child))])]
    (try+
      (is (= "ready" (deref ready budget-ms ::timeout)))
      (vreset! handles (targets child))
      (f child @handles)
      (finally
        (Thread/interrupted)
        (force-fixture! @handles)
        (.close ^java.io.Closeable (:out child))
        (future-cancel ready)))))

(comment (keys scripts))
