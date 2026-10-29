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
(ns ai.miniforge.config.file
  "Locked, atomic writes of an EDN config file such as ~/.miniforge/config.edn.
   A file that exists but is not a single EDN map is never overwritten."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.file-durability.interface :as durability]
   [ai.miniforge.messages.interface :as messages]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [slingshot.slingshot :refer [try+]])
  (:import
   [java.io PushbackReader StringReader]
   [java.nio.channels FileChannel]
   [java.nio.charset StandardCharsets]
   [java.nio.file OpenOption Paths StandardOpenOption]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private t
  (messages/create-translator "config/config/messages/system.edn" :config/system))

(def ^{:stratum 0} ^:private lock-timeout-ms
  "How long a writer waits for another process's config lock before giving up.
   Bounded so a contended lock never stalls a CLI command or HTTP handler."
  500)

(def ^{:stratum 0} ^:private lock-poll-ms
  "Pause between lock attempts: short next to `lock-timeout-ms`, without spinning."
  25)

(def ^{:stratum 0} ^:private lock-file-suffix
  "Every writer of `path` locks the sidecar file `<path>.lock`."
  ".lock")

;; A FileChannel lock belongs to the whole JVM: a second thread asking for it
;; gets OverlappingFileLockException instead of waiting. In-process writers
;; queue on this monitor first.
(defonce ^{:stratum 0} ^:private process-lock (Object.))

(defn ^{:stratum 0} ex-text [^Throwable e]
  (or (.getMessage e) (.getName (class e))))

(defn ^{:stratum 0} render
  "Pretty EDN for `config`. A caller's *print-length* would truncate it to `...`."
  [config]
  (binding [*print-length* nil *print-level* nil *print-namespace-maps* false]
    (with-out-str (pprint/pprint config))))

(defn ^{:stratum 0} publish-target
  "The file a write to `path` replaces and locks: a symlink's target, so writers
   going through the link and through the target take the same lock."
  [path]
  (if (fs/exists? path) (fs/real-path path) (fs/absolutize path)))

(defn ^{:stratum 0} read-forms
  "Every top-level EDN form in `text`. Throws on malformed EDN."
  [^String text]
  (let [reader (PushbackReader. (StringReader. text))
        eof    (Object.)]
    (loop [forms []]
      (let [form (edn/read {:eof eof} reader)]
        (if (identical? eof form) forms (recur (conj forms form)))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} write-atomically!
  "Rename a forced sibling temp file holding `text` over `target` (the
   `publish-target` of `path`), keeping its mode. nil, or an anomaly naming `path`."
  [path target ^String text]
  (let [tmp    (fs/create-temp-file {:dir (fs/parent target) :prefix ".config-" :suffix ".tmp"})
        failed #(anomaly/anomaly :fault (t :config-file/write-failed {:path path :error (:anomaly/message %)})
                                 {:config/path (str path) :config/cause %})]
    (try
      ;; Set the mode before any content lands in the temp file.
      (when (and (fs/exists? target) (not (fs/windows?)))
        (fs/set-posix-file-permissions tmp (fs/posix-file-permissions target)))
      (let [written (durability/write-temporary-bytes! (fs/file tmp) (.getBytes text StandardCharsets/UTF_8))]
        (if (anomaly/anomaly? written)
          (failed written)
          (do (fs/move tmp target {:atomic-move true :replace-existing true})
              ;; The new content is in place now; a failed fsync of the parent
              ;; directories means it may not survive a crash, not that the write
              ;; failed. Windows cannot open a directory to fsync it.
              (when-not (fs/windows?)
                (let [confirmed (durability/confirm! (fs/file target))]
                  (when (anomaly/anomaly? confirmed)
                    (anomaly/anomaly :fault (t :config-file/not-synced {:path path :error (:anomaly/message confirmed)})
                                     {:config/path (str path) :config/cause confirmed})))))))
      (finally
        (fs/delete-if-exists tmp)))))

(defn ^{:stratum 1} parse-config
  "nil for blank text or a lone nil, the map for one EDN map, else an anomaly."
  [path text]
  (let [unreadable #(anomaly/anomaly :invalid-input
                                     (t :config-file/unreadable {:path path :error %})
                                     {:config/path (str path) :config/error %})]
    (try+
      (let [[form :as forms] (read-forms text)]
        (cond
          (next forms)                 (unreadable (t :config-file/extra-forms {:count (count forms)}))
          (or (nil? form) (map? form)) form
          :else                        (unreadable (t :config-file/not-a-map {:found (.getName (class form))}))))
      (catch Exception e
        (unreadable (ex-text e))))))

(defn ^{:stratum 1} call-with-lock
  "`(thunk)` under the config lock for `path`, held on `<target>.lock`; an anomaly
   when the lock stays busy past `lock-timeout-ms` or the thunk throws."
  [path target thunk]
  (let [lock-file (Paths/get (str target lock-file-suffix) (make-array String 0))
        failure   #(anomaly/anomaly :fault (t :config-file/write-failed {:path path :error (ex-text %)})
                                    {:config/path (str path)})]
    (try+
      (locking process-lock
        (fs/create-dirs (fs/parent (fs/absolutize lock-file)))
        (with-open [channel (FileChannel/open lock-file (into-array OpenOption [StandardOpenOption/CREATE
                                                                                StandardOpenOption/WRITE]))]
          (let [deadline (+ (System/currentTimeMillis) lock-timeout-ms)
                ;; Hinted AutoCloseable: babashka does not expose FileLock.
                ^java.lang.AutoCloseable held (loop []
                                                (or (.tryLock channel)
                                                    (when (< (System/currentTimeMillis) deadline)
                                                      (Thread/sleep lock-poll-ms)
                                                      (recur))))]
            (if held
              (try (thunk) (finally (.close held)))
              (anomaly/anomaly :unavailable (t :config-file/locked {:path path}) {:config/path (str path)})))))
      (catch InterruptedException e
        (.interrupt (Thread/currentThread))
        (failure e))
      (catch Exception e
        (failure e)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} update-config!
  "See `ai.miniforge.config.interface.file/update-config!`."
  [path f]
  ;; Resolved once, so the lock, the read and the write all name the same file
  ;; even if a symlink at `path` is repointed meanwhile.
  (let [target (publish-target path)]
    (call-with-lock path target
                    (fn []
                      (let [before (when (fs/exists? target) (parse-config path (slurp (fs/file target))))]
                        (if (anomaly/anomaly? before)
                          before
                          (let [after (f before)]
                            (or (when (not= before after) (write-atomically! path target (render after)))
                                {:before before :after after}))))))))

(defn ^{:stratum 2} write-config!
  "See `ai.miniforge.config.interface.file/write-config!`."
  [path config]
  (let [target (publish-target path)]
    (call-with-lock path target #(or (write-atomically! path target (render config)) config))))
