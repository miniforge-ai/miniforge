;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.agent-environment.file-ledger-processes
  "Drives file ledger writer JVMs for the cross-process tests, released to
   write over stdin once every one has read; none outlives its test."
  (:require
   [clojure.edn :as edn])
  (:import
   [java.io BufferedReader IOException InputStreamReader]
   [java.lang ProcessBuilder$Redirect]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} k
  [#uuid "00000000-0000-0000-0000-00000000000a"
   #uuid "00000000-0000-0000-0000-00000000000b"
   #uuid "00000000-0000-0000-0000-00000000000c"])

(def ^{:stratum 0} writer-ns "ai.miniforge.agent-environment.file-ledger-writer")

(def ^{:stratum 0} step-timeout-ms
  "Bound on each step of a writer process, JVM start and namespace loading
   included, so a stuck writer fails the test rather than hanging it."
  120000)

(def ^{:stratum 0} timed-out "{:writer/timed-out true}")

(defn ^{:stratum 0} temp-dir
  []
  (str (.toFile (Files/createTempDirectory "ledger-process-test" (into-array FileAttribute [])))))

(defn- ^{:stratum 0} reader-of ^BufferedReader [^Process p] (BufferedReader. (InputStreamReader. (.getInputStream p))))

(defn- ^{:stratum 0} release!
  "Let `p` write; a writer that already exited is skipped, its exit code reporting it."
  [^Process p]
  (try
    (doto (.getOutputStream p) (.write (.getBytes "go\n")) (.flush))
    (catch IOException _ nil)))

(defn- ^{:stratum 0} destroy! [^Process p] (.destroyForcibly p))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} start-writer
  ^Process [mode dir]
  (let [java (str (System/getProperty "java.home") "/bin/java")
        command [java "-cp" (System/getProperty "java.class.path") "clojure.main" "-m" writer-ns mode dir (pr-str k)]]
    (-> (ProcessBuilder. ^java.util.List command)
        (.redirectError ProcessBuilder$Redirect/INHERIT)
        (.start))))

(defn- ^{:stratum 1} next-value
  [^BufferedReader reader]
  (edn/read-string (deref (future (.readLine reader)) step-timeout-ms timed-out)))

(defn- ^{:stratum 1} exit-code
  "`p`'s exit code within the step timeout; a writer still running then is
   killed and reported as `:writer/timed-out`, failing the test."
  [^Process p]
  (if (.waitFor p step-timeout-ms TimeUnit/MILLISECONDS)
    (.exitValue p)
    (do (.destroyForcibly p) :writer/timed-out)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} race!
  "Start `n` writers on `dir`, let all write once every one has read, and
   return their reads, results and exit codes; every writer is killed after."
  [dir n]
  (let [writers (vec (repeatedly n #(start-writer "race" dir)))]
    (try
      (let [readers (mapv reader-of writers)
            reads (mapv next-value readers)
            _ (run! release! writers)
            results (mapv next-value readers)
            exits (mapv exit-code writers)]
        {:reads reads :results results :exits exits})
      (finally (run! destroy! writers)))))

(defn ^{:stratum 2} read-in-fresh-process
  "What a fresh writer process reads from `dir`, with its exit code."
  [dir]
  (let [p (start-writer "read" dir)]
    (try
      (let [value (next-value (reader-of p))
            exit (exit-code p)]
        {:value value :exit exit})
      (finally (destroy! p)))))
