;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.lock-peer
  "Class 1 peer entry point; only exit status and private probe markers leave the process."
  (:require [ai.miniforge.file-durability.boundary.lock-peer-session :as session]
            [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [ai.miniforge.file-durability.lock-spec :as spec]
            [clojure.edn :as edn]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]
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

(defn- ^{:stratum 0} read-limits [text]
  (try+
    (let [limits (edn/read-string text)]
      (when (m/validate spec/Limits limits) limits))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} complete-arguments? [arguments]
  (let [[expected-runtime location encoded] arguments
        limits (when (= 3 (count arguments)) (read-limits encoded))]
    (boolean (and limits (session/completed? expected-runtime location limits)))))

(defn ^{:stratum 1} launch! [root {:peer/keys [^File executable classpath]} limits]
  (let [kind (other-runtime (protocol/runtime-kind))
        arguments (into [(.getPath executable) "-cp" classpath]
                        (concat (entrypoints kind) [peer-main (name kind) (str root) (pr-str limits)]))]
    (process/process arguments {:out :discard
                                :err :discard})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} -main [& arguments]
  (System/exit (if (complete-arguments? arguments) 0 1)))

(comment (other-runtime :jvm))
