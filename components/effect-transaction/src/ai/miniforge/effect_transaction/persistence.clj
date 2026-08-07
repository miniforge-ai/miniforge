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
(ns ai.miniforge.effect-transaction.persistence
  "Atomic filesystem publication and per-transaction locking."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.codec :as codec]
   [ai.miniforge.effect-transaction.messages :as msg]
   [clojure.edn :as edn]
   [clojure.java.io :as io])
  (:import
   [java.io File]
   [java.nio.channels FileChannel]
   [java.nio.file FileAlreadyExistsException Files StandardCopyOption StandardOpenOption]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} record-file
  ^File [dir id]
  (io/file dir (str id ".edn")))

(defn- ^{:stratum 0} lock-file
  ^File [dir id]
  (io/file dir (str id ".lock")))

(defn- ^{:stratum 0} temp-file
  ^File [^File target]
  (io/file (.getParentFile target)
           (str (.getName target) "." (random-uuid) ".tmp")))

(defn- ^{:stratum 0} read-failure
  [^File file ex]
  (anomaly/sub-anomaly :fault
                       :anomalies.effect-transaction/read-failed
                       (msg/t :store/read-failed)
                       {:effect/path (.getPath file)
                        :exception/message (some-> ex ex-message)}))

(defn- ^{:stratum 0} write-failure
  [^File file ex]
  (anomaly/sub-anomaly :fault
                       :anomalies.effect-transaction/write-failed
                       (msg/t :store/write-failed)
                       {:effect/path (.getPath file)
                        :exception/message (some-> ex ex-message)}))

(defn- ^{:stratum 0} id-conflict
  [record]
  (anomaly/sub-anomaly :conflict
                       :anomalies.effect-transaction/id-conflict
                       (msg/t :proposal/id-conflict)
                       {:effect/id (:effect/id record)}))

(defn- ^{:stratum 0} lock-conflict
  [id]
  (anomaly/sub-anomaly :conflict
                       :anomalies.effect-transaction/lock-conflict
                       (msg/t :store/lock-conflict)
                       {:effect/id id}))

(defn- ^{:stratum 0} anomaly-value
  [value]
  (when (anomaly/anomaly? value) value))

(defn- ^{:stratum 0} link-target!
  [^File tmp ^File target]
  (Files/createLink (.toPath target) (.toPath tmp)))

(defn- ^{:stratum 0} move-target!
  [^File tmp ^File target]
  (Files/move (.toPath tmp)
              (.toPath target)
              (into-array java.nio.file.CopyOption
                          [StandardCopyOption/ATOMIC_MOVE
                           StandardCopyOption/REPLACE_EXISTING])))

(defn- ^{:stratum 0} call-with-lock
  [lock thunk]
  (try
    (thunk)
    (finally
      (.release lock))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-file
  [^File file]
  (try
    (codec/<-wire (edn/read-string (slurp file :encoding "UTF-8")))
    (catch Exception ex
      (read-failure file ex))))

(defn- ^{:stratum 1} persist!
  [^File target record publish!]
  (let [^File tmp (temp-file target)]
    (try
      (io/make-parents target)
      (spit tmp (pr-str (codec/->wire record)) :encoding "UTF-8")
      (publish! tmp target)
      record
      (catch FileAlreadyExistsException _
        (id-conflict record))
      (catch Exception ex
        (write-failure target ex))
      (finally
        (.delete tmp)))))

(defn- ^{:stratum 1} open-channel
  [^File file options]
  (try
    (io/make-parents file)
    (FileChannel/open (.toPath file) options)
    (catch Exception ex
      (write-failure file ex))))

(defn- ^{:stratum 1} attempt-lock
  [^FileChannel channel ^File file id]
  (try
    (or (.tryLock channel) (lock-conflict id))
    (catch Exception ex
      ;; Babashka does not expose OverlappingFileLockException as a
      ;; resolvable class, so keep this JVM/BB-compatible boundary check.
      (if (= "java.nio.channels.OverlappingFileLockException"
             (.getName (class ex)))
        (lock-conflict id)
        (write-failure file ex)))))

(defn- ^{:stratum 1} list-files
  [^File directory]
  (try
    (if (.isDirectory directory)
      (or (.listFiles directory) (read-failure directory nil))
      [])
    (catch Exception ex
      (read-failure directory ex))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create!
  [dir record]
  (persist! (record-file dir (:effect/id record)) record link-target!))

(defn ^{:stratum 2} replace!
  [dir record]
  (persist! (record-file dir (:effect/id record)) record move-target!))

(defn ^{:stratum 2} with-record-lock
  [dir id thunk]
  (let [^File file (lock-file dir id)
        options (into-array StandardOpenOption
                            [StandardOpenOption/CREATE
                             StandardOpenOption/WRITE])
        opened (open-channel file options)]
    (if (anomaly/anomaly? opened)
      opened
      (with-open [^FileChannel channel opened]
        (let [lock (attempt-lock channel file id)]
          (if (anomaly/anomaly? lock)
            lock
            (call-with-lock lock thunk)))))))

(defn ^{:stratum 2} read-record
  [dir id]
  (let [^File file (record-file dir id)]
    (when (.exists file)
      (read-file file))))

(defn ^{:stratum 2} list-records
  [dir]
  (let [^File directory (io/file dir)
        files (list-files directory)
        xform (comp (filter #(.endsWith (.getName ^File %) ".edn"))
                    (map read-file))]
    (if (anomaly/anomaly? files)
      files
      (let [records (into [] xform files)]
        (or (some anomaly-value records) records)))))
