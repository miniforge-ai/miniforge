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
  "Drives file ledger writer processes for the cross-process tests: each
   is its own JVM on this classpath, released to write over stdin once
   every one of them has read."
  (:require
   [clojure.edn :as edn])
  (:import
   [java.io BufferedReader InputStreamReader]
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
  [^Process p]
  (doto (.getOutputStream p) (.write (.getBytes "go\n")) (.flush)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} start-writer
  "A writer JVM on this test's classpath, running `mode` over `dir`."
  ^Process [mode dir]
  (let [java (str (System/getProperty "java.home") "/bin/java")
        command [java "-cp" (System/getProperty "java.class.path") "clojure.main" "-m" writer-ns mode dir (pr-str k)]]
    (-> (ProcessBuilder. ^java.util.List command)
        (.redirectError ProcessBuilder$Redirect/INHERIT)
        (.start))))

(defn- ^{:stratum 1} next-value
  [^BufferedReader reader]
  (edn/read-string (deref (future (.readLine reader)) step-timeout-ms timed-out)))

(defn- ^{:stratum 1} finished? [^Process p] (.waitFor p step-timeout-ms TimeUnit/MILLISECONDS))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} race!
  "Start `n` writers on `dir`; once every one has read, let all of them
   write. Returns what each read and what each write returned."
  [dir n]
  (let [writers (vec (repeatedly n #(start-writer "race" dir)))
        readers (mapv reader-of writers)
        reads (mapv next-value readers)
        _ (run! release! writers)
        results (mapv next-value readers)]
    (run! finished? writers)
    {:reads reads :results results}))

(defn ^{:stratum 2} read-in-fresh-process
  [dir]
  (let [p (start-writer "read" dir)
        value (next-value (reader-of p))]
    (finished? p)
    value))
