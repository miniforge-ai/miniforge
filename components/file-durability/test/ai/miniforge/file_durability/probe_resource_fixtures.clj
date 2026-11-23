;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.probe-resource-fixtures
  "Ownership observations over actual probe process records and private scratch."
  (:require [ai.miniforge.file-durability.boundary.probe-resources :as resources]
            [ai.miniforge.file-durability.probe-cleanup-fixtures :as native]
            [ai.miniforge.file-durability.publication-support :as support]
            [babashka.fs :as fs]
            [clojure.test :refer [is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} delete-scratch
  (ns-resolve 'ai.miniforge.file-durability.boundary.probe-resources 'delete!))

(def ^{:stratum 0} signal-native
  (ns-resolve 'ai.miniforge.file-durability.boundary.native-process-exit 'signal!))

(defn ^{:stratum 0} capture! [parent timeout-ms operation]
  (try+
    (let [value (resources/call! parent "probe-fixture-" timeout-ms operation)]
      {:returned value})
    (catch Object value {:thrown (:throwable &throw-context) :object value})))

(defn ^{:stratum 0} register! [observed process outcome root child]
  (vreset! child process)
  (vreset! observed root)
  (is (= "rwx------" (support/mode (fs/file root))))
  (if (instance? Throwable outcome) (throw outcome) outcome))

(defn ^{:stratum 0} delete-after-death! [handles delete! root]
  (is (every? native/dead? handles))
  (delete! root))

(comment (support/with-root #(capture! % 1000 (constantly :complete))))
