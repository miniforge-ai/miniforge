;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.lock-peer
  "Class 1 peer entry point; only exit status and private probe markers leave the process."
  (:require [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [ai.miniforge.file-durability.boundary.protected-directory :as directory]
            [ai.miniforge.file-durability.boundary.publication-probe :as publication]
            [clojure.java.io :as io]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} peer-completed? [expected-runtime location]
  (try+
    (let [root (directory/existing! (io/file location))
          runtime (keyword expected-runtime)]
      (and (= runtime (protocol/runtime-kind))
           (= :publication-verified (publication/check! root))
           (protocol/peer! root)))
    (catch InterruptedException _ (.interrupt (Thread/currentThread)) false)
    (catch Object _ false)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} -main [& arguments]
  (let [[expected-runtime location] arguments
        complete? (and (= 2 (count arguments)) (peer-completed? expected-runtime location))]
    (System/exit (if complete? 0 1))))
