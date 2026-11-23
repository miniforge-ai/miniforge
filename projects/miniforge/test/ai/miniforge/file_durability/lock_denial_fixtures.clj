;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.lock-denial-fixtures
  "Native fault peers; production marker and lock policy remains unchanged."
  (:require [babashka.process :as process]
            [clojure.test :refer [is]])
  (:import [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} fixture-exit-ms
  "Independent teardown after assertions, never a grace period for the production result."
  2000)

(defn ^{:stratum 0} delete-after-death! [observed delete! root]
  (when-let [[child] @observed]
    (is (not (.isAlive ^Process (:proc child)))))
  (delete! root))

(def ^{:stratum 0} scenarios
  "Fault peers speak the marker protocol but cannot prove mutual exclusion."
  {:premature-exit "exit 0"
   :timeout "exec sleep 30"
   :false-lock "touch \"$1/peer-excluded\"; while ! test -f \"$1/host-released\"; do sleep 0.01; done; touch \"$1/peer-holding\"; while ! test -f \"$1/host-observed-exclusion\"; do sleep 0.01; done"})

(defn ^{:stratum 0} record-wait! [seen await root marker until poll-ms]
  (swap! seen conj until)
  (await root marker until poll-ms))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} dispose! [observed]
  (Thread/interrupted)
  (when-let [[child] @observed]
    (let [^Process worker (:proc child)]
      (.destroyForcibly worker)
      (is (.waitFor worker fixture-exit-ms TimeUnit/MILLISECONDS)))))

(defn ^{:stratum 1} launch! [mode observed root _configuration _limits]
  (let [child (process/process ["/bin/sh" "-c" (get scenarios mode) "lock-denial-fixture" (str root)])]
    (vreset! observed [child root])
    child))

(comment (keys scenarios))
