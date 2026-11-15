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
(ns ai.miniforge.agent-environment.ledger.disk
  "File mechanics of the durable ledger store, each returning its result
   or a ledger-unavailable anomaly: a key's record and lock files, an EDN
   codec that writes Instants under `#mf/instant` (`#inst` reads back as a
   Date, which would make every stored request look changed after a
   restart), whole-record reads, publication forced to stable storage
   before it returns, and a lock held against other threads and processes."
  (:require
   [ai.miniforge.agent-environment.interface.ledger :as ledger]
   [ai.miniforge.agent-environment.refusal :as refusal]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.content-hash.interface :as content-hash]
   [ai.miniforge.file-durability.interface :as durability]
   [clojure.edn :as edn]
   [clojure.walk :as walk]
   [malli.core :as m])
  (:import
   [java.io File]
   [java.nio.channels FileChannel]
   [java.nio.file Files StandardCopyOption StandardOpenOption]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} record-suffix ".edn")

(def ^{:stratum 0} lock-suffix ".lock")

(def ^{:stratum 0} temp-prefix
  "Temporary files sit beside the records, so publishing one is a rename
   within a directory; their suffix keeps them out of `record-files`."
  ".ledger-")

(def ^{:stratum 0} temp-suffix ".tmp")

(def ^{:stratum 0} lock-stripes
  "Monitors this JVM's threads take before a key's file lock, which
   excludes other processes but throws when this JVM already holds it.
   Keys share a stripe by hash, so the monitors stay bounded however many
   keys are written; 64 keeps unrelated keys from queueing behind each
   other in all but heavy concurrent use."
  (vec (repeatedly 64 #(Object.))))

(def ^{:stratum 0} StoredRecord
  "What a record file must hold to be read; anything else is corrupt."
  [:map [:ledger/key vector?] [:ledger/entry [:maybe map?]] [:ledger/revision pos-int?]])

(defn- ^{:stratum 0} parse-instant [s] (Instant/parse s))

(defn- ^{:stratum 0} error-paths
  "Where an explained value departs from its schema, as plain data."
  [explained]
  (mapv :path (:errors explained)))

(defn- ^{:stratum 0} tagged
  [x]
  (if (instance? Instant x) (tagged-literal 'mf/instant (str x)) x))

(defn- ^{:stratum 0} key-name
  "The file name stem for key `k`: one key names the same files in every
   process."
  [k]
  (content-hash/content-hash (pr-str k)))

(defn- ^{:stratum 0} unavailable
  [operation ^File file detail]
  (refusal/reason-anomaly :anomalies.agent-environment/ledger-unavailable
                          {:ledger/operation operation :ledger/path (.getPath file) :ledger/detail detail}))

(defn- ^{:stratum 0} move-into-place!
  ^File [^File tmp ^File target]
  (Files/move (.toPath tmp) (.toPath target)
              (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
  target)

(defn- ^{:stratum 0} locked-call
  "Hold `lock-file`'s file lock, blocking until it is free, while `thunk` runs."
  [^File lock-file thunk]
  (.mkdirs (.getParentFile lock-file))
  (with-open [channel (FileChannel/open (.toPath lock-file)
                                        (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
    (.lock channel)
    (thunk)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} encode
  ^bytes [record]
  (binding [*print-length* nil *print-level* nil *print-meta* false *print-namespace-maps* false]
    (.getBytes ^String (pr-str (walk/postwalk tagged record)) "UTF-8")))

(defn- ^{:stratum 1} with-io-failure
  "Run `thunk` at the store's file I/O boundary: a thrown exception becomes
   the ledger being unavailable, naming `operation` and `file`."
  [operation ^File file thunk]
  (try
    (thunk)
    (catch Exception e
      (unavailable operation file (ex-message e)))))

(defn- ^{:stratum 1} written-via-temp!
  "Publish `bytes` as `target` through a fresh temporary file; the
   temporary is removed whether or not the rename happened."
  [^File dir ^File target bytes record]
  (.mkdirs dir)
  (let [tmp (.toFile (Files/createTempFile (.toPath dir) temp-prefix temp-suffix (into-array FileAttribute [])))]
    (try
      (let [published (durability/publish-bytes! tmp bytes (partial move-into-place! tmp target))]
        (if (anomaly/anomaly? published) (unavailable :write target (:anomaly/message published)) record))
      (finally (.delete tmp)))))

(defn ^{:stratum 1} record-file ^File [dir k] (File. ^File dir (str (key-name k) record-suffix)))

(defn- ^{:stratum 1} lock-file ^File [dir k] (File. ^File dir (str (key-name k) lock-suffix)))

(defn- ^{:stratum 1} monitor-for
  [^File file]
  (nth lock-stripes (mod (hash (.getAbsolutePath file)) (count lock-stripes))))

(defn- ^{:stratum 1} record-file?
  [^File file]
  (.endsWith (.getName file) record-suffix))

(defn- ^{:stratum 1} decode
  "The record `text` holds, or the store's anomaly when it is not one."
  [^File file text]
  (let [record (edn/read-string {:readers {'mf/instant parse-instant}} text)]
    (if-let [explained (m/explain StoredRecord record)]
      (unavailable :decode file (error-paths explained))
      record)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} record-files
  "Every record file under `dir`; none before the first write."
  [^File dir]
  (with-io-failure :list dir #(filterv record-file? (or (.listFiles dir) []))))

(defn ^{:stratum 2} publish!
  "Write `record` as `target`, forced to stable storage with its directory
   entry before `record` is returned."
  [^File dir ^File target record]
  (with-io-failure :write target (partial written-via-temp! dir target (encode record) record)))

(defn ^{:stratum 2} with-lock
  "Run `thunk` holding key `k`'s lock in `dir` against every other writer,
   in this process or another; returns its result or the lock's anomaly."
  [dir k thunk]
  (let [file (lock-file dir k)
        monitor (monitor-for file)]
    (locking monitor
      (with-io-failure :lock file (partial locked-call file thunk)))))

(defn ^{:stratum 2} read-record
  "The record in `file`, or the store's absent value when there is none.
   Records are only renamed into place, so a read sees a whole one."
  [^File file]
  (with-io-failure :read file #(if (.exists file) (decode file (slurp file :encoding "UTF-8")) ledger/absent)))
