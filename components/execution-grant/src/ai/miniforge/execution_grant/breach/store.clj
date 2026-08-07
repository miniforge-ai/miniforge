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
(ns ai.miniforge.execution-grant.breach.store
  "Append-only filesystem storage for execution-grant breaches.

   One complete EDN file is published per breach. An exclusive hard
   link makes reusing an id unable to replace the original evidence."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.messages :as msg]
   [ai.miniforge.execution-grant.schema :as schema]
   [ai.miniforge.execution-grant.temporal :as temporal]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [malli.core :as m])
  (:import
   [java.io File]
   [java.nio.file FileAlreadyExistsException Files]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} ->iso
  ^String [value]
  (.toString (temporal/->instant value)))

(defn- ^{:stratum 0} breach-file?
  [^File file]
  (.endsWith (.getName file) ".edn"))

(defn- ^{:stratum 0} principal-breach?
  [principal breach]
  (or (nil? principal)
      (= principal (:breach/principal breach))))

(defn- ^{:stratum 0} anomaly-value
  [value]
  (when (anomaly/anomaly? value) value))

(defn- ^{:stratum 0} invalid-record
  [breach]
  (anomaly/sub-anomaly :invalid-input
                       :anomalies.execution-grant/invalid-breach
                       (msg/t :breach/invalid)
                       {:breach breach}))

(defn- ^{:stratum 0} read-failure
  [^File file ex]
  (anomaly/sub-anomaly :fault
                       :anomalies.execution-grant/breach-read-failed
                       (msg/t :breach/read-failed)
                       {:breach/path (.getPath file)
                        :exception/message (some-> ex ex-message)}))

(defn- ^{:stratum 0} invalid-stored-record
  [^File file value]
  (anomaly/sub-anomaly :fault
                       :anomalies.execution-grant/breach-read-failed
                       (msg/t :breach/read-failed)
                       {:breach/path (.getPath file)
                        :breach/value value}))

(defn- ^{:stratum 0} write-failure
  [^File target ex]
  (anomaly/sub-anomaly :fault
                       :anomalies.execution-grant/breach-write-failed
                       (msg/t :breach/write-failed)
                       {:breach/path (.getPath target)
                        :exception/message (ex-message ex)}))

(defn- ^{:stratum 0} id-conflict
  [breach]
  (anomaly/sub-anomaly :conflict
                       :anomalies.execution-grant/breach-id-conflict
                       (msg/t :breach/id-conflict)
                       {:breach/id (:breach/id breach)}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} breach-files
  [^File dir]
  (try
    (cond
      (not (.exists dir)) []
      (not (.isDirectory dir)) (read-failure dir nil)
      :else (if-let [files (.listFiles dir)]
              (filterv breach-file? files)
              (read-failure dir nil)))
    (catch Exception ex
      (read-failure dir ex))))

(defn- ^{:stratum 1} read-record
  [^File file]
  (try
    (let [wire-record (edn/read-string (slurp file :encoding "UTF-8"))
          record (update wire-record :breach/at #(Instant/parse %))]
      (if (m/validate schema/Breach record)
        record
        (invalid-stored-record file record)))
    (catch Exception ex
      (read-failure file ex))))

(defn- ^{:stratum 1} persist!
  [^File target breach]
  (let [tmp-name (str (.getName target) "." (random-uuid) ".tmp")
        ^File tmp (io/file (.getParentFile target) tmp-name)]
    (try
      (io/make-parents target)
      (spit tmp (pr-str (update breach :breach/at ->iso)) :encoding "UTF-8")
      (Files/createLink (.toPath target) (.toPath tmp))
      breach
      (catch FileAlreadyExistsException _
        (id-conflict breach))
      (catch Exception ex
        (write-failure target ex))
      (finally
        (.delete tmp)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} history
  "Read breach history, failing closed on inaccessible or corrupt evidence."
  ([dir] (history dir nil))
  ([dir principal]
   (let [files (breach-files (io/file dir))]
     (if (anomaly/anomaly? files)
       files
       (let [records (mapv read-record files)]
         (or (some anomaly-value records)
             (filterv (partial principal-breach? principal) records)))))))

(defn ^{:stratum 2} record!
  "Publish one complete breach record without replacing existing evidence."
  [dir breach]
  (if (m/validate schema/Breach breach)
    (persist! (io/file dir (str (:breach/id breach) ".edn")) breach)
    (invalid-record breach)))
