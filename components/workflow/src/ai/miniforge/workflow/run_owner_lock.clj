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
(ns ai.miniforge.workflow.run-owner-lock
  "The run's owner lock: an exclusive OS file lock a running pipeline holds
   on its run id for its whole lifetime, so a second process (a resume, a
   launcher retry) cannot run the same run at the same time.

   Unlike the checkpoint store's run lock (256 shared slot files, held for
   a write and waited for), this lock is one file per run, taken with a
   non-blocking tryLock and held until the run ends: a busy lock means a
   live owner, refused at once. The file records the owner's pid and start
   time, so a refusal can name it. The OS drops the lock when the owner
   process dies, so a crashed run never leaves its id locked.

   Files live under `<checkpoint-root>/.owners/`, a dot directory the
   checkpoint GC passes over, outside any run directory (a file there
   would count as a checkpoint)."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.workflow.messages :as messages]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [slingshot.slingshot :refer [throw+ try+]])
  (:import
   (java.nio ByteBuffer)
   (java.nio.channels FileChannel)
   (java.nio.charset StandardCharsets)
   (java.nio.file LinkOption OpenOption StandardOpenOption)
   (java.time Instant)))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} owner-file
  "Run `run-id`'s owner lock file under `checkpoint-root`."
  [checkpoint-root run-id]
  (fs/path checkpoint-root ".owners" (str run-id ".lock")))

(defn- ^{:stratum 0} try-lock
  "The lock on `channel`, or nil when another holder has it (another
   process, or this one: the JVM reports its own overlapping lock by
   throwing OverlappingFileLockException, matched by name since babashka
   does not expose the class)."
  ^java.lang.AutoCloseable [^FileChannel channel]
  (try
    (.tryLock channel)
    (catch Exception e
      (if (= "java.nio.channels.OverlappingFileLockException" (.getName (class e)))
        nil
        (throw e)))))

(defn- ^{:stratum 0} read-owner
  "The owner record in `channel`'s file, or {} when it is empty or not one."
  [^FileChannel channel]
  (let [buffer (ByteBuffer/allocate (int (min 4096 (.size channel))))]
    (.read channel buffer 0)
    (let [content (String. (.array buffer) 0 (.position buffer) StandardCharsets/UTF_8)
          record  (try (edn/read-string content) (catch Exception _ nil))]
      (if (map? record) record {}))))

(defn- ^{:stratum 0} write-owner!
  "Replace `channel`'s file content with this process's owner record."
  [^FileChannel channel record]
  (.truncate channel 0)
  (.write channel (ByteBuffer/wrap (.getBytes (pr-str record) StandardCharsets/UTF_8)) 0)
  (.force channel true))

(defn- ^{:stratum 0} busy
  [run-id owner]
  (anomaly/anomaly :conflict
                   (messages/t :run/owner-busy {:run-id (str run-id)
                                                :pid (get owner :owner/pid "?")
                                                :started-at (get owner :owner/started-at "?")})
                   {:workflow/run-id run-id :run/owner owner}))

(defn- ^{:stratum 0} failed
  [run-id path e]
  (anomaly/exception-anomaly :unavailable
                             (messages/t :run/owner-lock-failed
                                         {:run-id (str run-id)
                                          :error (or (ex-message e) (.getName (class e)))})
                             {:workflow/run-id run-id :run/owner-file (str path)}
                             e))

(defn- ^{:stratum 0} open-channel
  "Open `path` for read and write, creating it (and its directory) when
   `create?`. Refuses a symlinked file or directory."
  ^FileChannel [path create?]
  (when (or (fs/sym-link? (fs/parent path)) (fs/sym-link? path))
    (throw (ex-info "owner lock path is a symbolic link" {:path (str path)})))
  (when create? (fs/create-dirs (fs/parent path)))
  (FileChannel/open path (into-array OpenOption
                                     (cond-> [StandardOpenOption/READ StandardOpenOption/WRITE
                                              LinkOption/NOFOLLOW_LINKS]
                                       create? (conj StandardOpenOption/CREATE)))))

(defn ^{:stratum 0} release!
  "Release an owner lock acquire! returned. The file stays."
  [{:owner/keys [channel lock]}]
  (.close ^java.lang.AutoCloseable lock)
  (.close ^java.lang.AutoCloseable channel))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} acquire!
  "Take run `run-id`'s owner lock under `checkpoint-root` without waiting,
   and record this process as the owner. Returns the held lock (for
   release!), a :conflict anomaly naming the live owner when it is held,
   or an :unavailable anomaly on an I/O error."
  [checkpoint-root run-id]
  (let [path (owner-file checkpoint-root run-id)]
    (try+
      (let [channel (open-channel path true)]
        (try+
          (if-let [lock (try-lock channel)]
            (do (write-owner! channel {:owner/run-id (str run-id)
                                       :owner/pid (.pid (java.lang.ProcessHandle/current))
                                       :owner/started-at (str (Instant/now))})
                {:owner/channel channel :owner/lock lock :owner/run-id run-id})
            (let [owner (read-owner channel)]
              (.close channel)
              (busy run-id owner)))
          (catch Exception e
            (.close channel)
            (throw e))))
      (catch Exception e
        (failed run-id path e)))))

(defn ^{:stratum 1} owner
  "The live owner of run `run-id` under `checkpoint-root` (its record, {}
   when unreadable), nil when no process holds the lock, or an
   :unavailable anomaly on an I/O error."
  [checkpoint-root run-id]
  (let [path (owner-file checkpoint-root run-id)]
    (try+
      (when (fs/exists? path {:nofollow-links true})
        (with-open [channel (open-channel path false)]
          (if-let [lock (try-lock channel)]
            (do (.close lock) nil)
            (read-owner channel))))
      (catch java.nio.file.NoSuchFileException _ nil)
      (catch Exception e
        (failed run-id path e)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-run-owner
  "`(f)` while holding run `run-id`'s owner lock under `checkpoint-root`;
   `(f)` alone when `run-id` is nil (a run id minted fresh, which no other
   process can hold). When the lock is busy or cannot be taken, `f` does
   not run and the anomaly is thrown: the run must not start."
  [checkpoint-root run-id f]
  (if (nil? run-id)
    (f)
    (let [held (acquire! checkpoint-root run-id)]
      (if (anomaly/anomaly? held)
        (throw+ held)
        (try (f)
             (finally (release! held)))))))
