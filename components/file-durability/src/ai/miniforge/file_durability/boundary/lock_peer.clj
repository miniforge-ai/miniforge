;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.lock-peer
  "Class 1 peer entry point; only exit status and private probe markers leave the process."
  (:require [ai.miniforge.file-durability.boundary.lock-peer-session :as session]
            [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [babashka.process :as process])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} peer-main
  "Both runtimes load the same implementation from the selected installation."
  "ai.miniforge.file-durability.boundary.lock-peer")

(def ^{:stratum 0 :private true} entrypoints
  "Construct argv without accepting a shell command or additional flags."
  {:jvm ["clojure.main" "-m"]
   :babashka ["-m"]})

(defn- ^{:stratum 0} other-runtime [runtime]
  (if (= runtime :babashka) :jvm :babashka))

(defn ^{:stratum 0} -main [& arguments]
  (let [[expected-runtime location] arguments
        complete? (and (= 2 (count arguments)) (session/completed? expected-runtime location))]
    (System/exit (if complete? 0 1))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} launch! [root {:peer/keys [^File executable classpath]}]
  (let [kind (other-runtime (protocol/runtime-kind))
        arguments (into [(.getPath executable) "-cp" classpath]
                        (concat (entrypoints kind) [peer-main (name kind) (str root)]))]
    (process/process arguments {:out :discard
                                :err :discard})))

(comment (other-runtime :jvm))
