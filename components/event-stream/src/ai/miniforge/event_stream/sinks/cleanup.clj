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
(ns ai.miniforge.event-stream.sinks.cleanup
  "Retention for stored events. `cleanup-stale-events!` prunes, or
   archives, what has outlived its TTL under the events directory;
   `retention-hold` lets a store outside the event stream keep a
   workflow's events past it."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.event-stream.sinks :as sinks]
   [ai.miniforge.event-stream.storage-layout :as layout]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]])
  (:import
   [java.nio.file Files LinkOption OpenOption StandardCopyOption StandardOpenOption]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private default-event-ttl-ms
  "How long cleanup keeps stored events — 7 days: an archived workflow
   for this long after its archive, an operator event or a legacy
   workflow directory for this long after its last write. Long enough to
   debug a failed run days later; short enough that the on-disk event log
   doesn't grow unbounded on a busy fleet."
  (* 7 24 60 60 1000))

(def ^{:stratum 0} ^:private warned-runs
  "Live run directories cleanup has warned about keeping, so each is
   reported once per process."
  (atom #{}))

(def ^{:stratum 0} ^:private manifest-filename
  "The per-workflow manifest's file name, read from the manifest
   contract defaults. The manifest namespace is JVM-only; this one also
   loads under Babashka, so it reads the name from the resource."
  (:manifest-filename
   (config/load-config-resource "config/event-stream/manifest-defaults.edn"
                                [:manifest-filename])))

(defn- ^{:stratum 0} walk-tree
  "`dir` and every entry under it, parents before children. Unlike
   `file-seq` it does not descend into a symlinked directory below `dir`
   (`dir` itself may be one), so a link inside the events tree cannot
   lead cleanup to files outside it."
  [^java.io.File dir]
  (let [real-dir? (fn [^java.io.File f]
                    (and (.isDirectory f) (not (Files/isSymbolicLink (.toPath f)))))
        children (fn [^java.io.File f] (seq (.listFiles f)))]
    (cons dir (mapcat #(tree-seq real-dir? children %)
                      (when (.isDirectory dir) (children dir))))))

(defn- ^{:stratum 0} delete-all!
  "Delete each of `files` in order; return how many regular files went.
   Directories in `files` are deleted too but not counted, and only go
   once empty, so pass children before their parents."
  [files]
  (reduce (fn [n ^java.io.File f]
            (let [file? (.isFile f)]
              (if (and (.delete f) file?) (inc n) n)))
          0
          files))

(defn- ^{:stratum 0} child-dirs
  "The directories directly inside `dir`, symlinks excluded; empty when
   `dir` does not exist."
  [^java.io.File dir]
  (filter (fn [^java.io.File f]
            (and (.isDirectory f) (not (Files/isSymbolicLink (.toPath f)))))
          (.listFiles dir)))

(defn- ^{:stratum 0} archive-expired?
  "True when the archived workflow directory `dir` finished archiving
   before `cutoff` (epoch ms). The archive time is the mtime of its
   `archived.marker`: the archive creates the marker once, as its last
   step, and nothing writes it afterwards. A directory without the
   marker is a half-finished archive that recovery still has to
   complete, so it never counts as expired."
  [^java.io.File dir cutoff]
  (let [marker (io/file dir (layout/archived-marker-filename))]
    (and (.isFile marker) (< (.lastModified marker) cutoff))))

(defmulti ^{:stratum 0} retention-hold
  "Whether a store outside the event stream still needs a workflow's
   stored events past their TTL. Dispatches on a hold key; each owner
   registers one method under its own key (the workflow checkpoint store
   holds a run's events while its checkpoint exists, so a resume does not
   find a checkpoint and no events). `cleanup-stale-events!` keeps an
   expired archived or legacy workflow directory when any registered hold
   answers true for its directory name. Args: hold key, workflow id
   (the directory name, a string), and the events root being cleaned (so
   a hold can read the workflow's own events)."
  (fn [hold _workflow-id _events-dir] hold))

(defmethod ^{:stratum 0} retention-hold :default [_hold _workflow-id _events-dir] false)

(defn- ^{:stratum 0} legacy-workflow-expired?
  "True when `dir`, a directory directly under the events root outside
   the live, archived and operator trees, is a pre-archive flat workflow
   directory nobody has written since `cutoff` (epoch ms): a visible
   name; nothing inside but event and temp files (no subdirectory, no
   manifest, nothing another tool keeps there); and the directory and
   every file in it last modified before `cutoff`. The file sink no
   longer writes this layout, so such a directory is a finished or
   abandoned run, and it goes whole or not at all."
  [^java.io.File dir cutoff manifest-name]
  (let [children (.listFiles dir)]
    (and (some? children)
         (not (str/starts-with? (.getName dir) "."))
         (< (.lastModified dir) cutoff)
         (every? (fn [^java.io.File f]
                   (let [n (.getName f)]
                     (and (.isFile f)
                          (not= manifest-name n)
                          (or (str/ends-with? n ".json")
                              (str/ends-with? n ".transit.tmp"))
                          (< (.lastModified f) cutoff))))
                 children))))

(defn- ^{:stratum 0} no-events?
  "True when workflow directory `dir` holds no event file."
  [^java.io.File dir]
  (not-any? #(str/ends-with? (.getName ^java.io.File %) ".json") (.listFiles dir)))

(defn- ^{:stratum 0} idle-unmanaged-run?
  "True when live workflow directory `dir` has a visible name, no
   manifest (no JVM runner manages it), nothing but event and temp files
   besides an `archived.marker` an interrupted archive left (a regular
   file: a symlink or a directory of that name makes the run ineligible),
   and neither it nor any event file was modified since `cutoff`."
  [^java.io.File dir cutoff manifest-name marker-name]
  (let [children (.listFiles dir)]
    (and (some? children)
         (not (str/starts-with? (.getName dir) "."))
         (< (.lastModified dir) cutoff)
         (every? (fn [^java.io.File f]
                   (let [n (.getName f)]
                     (or (and (= marker-name n)
                              (Files/isRegularFile (.toPath f)
                                                   (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
                         (and (.isFile f)
                              (not= manifest-name n)
                              (or (str/ends-with? n ".json") (str/ends-with? n ".transit.tmp"))
                              (< (.lastModified f) cutoff)))))
                 children))))

(defn- ^{:stratum 0} run-verdict
  ":archive when the newest workflow lifecycle event in live run `dir`,
   by event timestamp then sequence number across all of its event files,
   ends the run: :workflow/completed (whatever its status),
   :workflow/failed or :workflow/cancelled. Only a run idle for the TTL
   is asked about, and resuming an archived run starts a new attempt from
   archived/. :keep when the newest is a start or a resume (running, or
   crashed). When any file in `dir` is a symlink, or an event file cannot
   be read or decoded, a string saying which: the run stays, since the
   event it cannot see could be a later start. File mtimes play no part
   here; only the idle check reads them. Reads the transit-JSON this
   namespace writes."
  [^java.io.File dir]
  (let [children (.listFiles dir)
        parse (fn [^java.io.File f]
                (try+ (json/parse-string (slurp f :encoding "UTF-8"))
                      (catch Exception _ nil)))
        order (fn [e] [(some-> (get e "~:event/timestamp") (subs 2) Instant/parse .toEpochMilli)
                       (get e "~:event/sequence-number" 0)])
        lifecycle #{"~:workflow/started" "~:workflow/resumed" "~:workflow/completed"
                    "~:workflow/failed" "~:workflow/cancelled"}
        symlink (some #(when (Files/isSymbolicLink (.toPath ^java.io.File %)) %) children)
        events  (when-not symlink
                  (->> children
                       (filter #(str/ends-with? (.getName ^java.io.File %) ".json"))
                       (map (juxt identity parse))))
        bad     (some (fn [[f e]] (when-not (map? e) f)) events)
        newest  (->> (map second events)
                     (filter #(lifecycle (get % "~:event/type")))
                     (sort-by order #(compare %2 %1))
                     first)]
    (cond
      symlink (str "symlinked file " (.getName ^java.io.File symlink))
      bad     (str "unreadable or undecodable event file " (.getName ^java.io.File bad))
      (#{"~:workflow/completed" "~:workflow/failed" "~:workflow/cancelled"}
       (get newest "~:event/type"))
      :archive
      :else :keep)))

(defn- ^{:stratum 0} archive-completed-run!
  "Archive live workflow directory `dir` into `archived-root` as the JVM
   runner does at the end of a run: write its `archived.marker` first,
   then move the directory in one atomic rename, so an archived
   directory always has its marker; an interrupted attempt leaves the
   run in live/ for the next cleanup. Returns 1 when moved, else 0."
  [^java.io.File dir ^java.io.File archived-root marker-name]
  (let [target (io/file archived-root (.getName dir))]
    (if (.exists target)
      0
      (try+
        (.mkdirs archived-root)
        ;; NOFOLLOW_LINKS: a symlinked marker fails here instead of
        ;; truncating whatever it points to outside the events tree.
        (.close (Files/newOutputStream (.toPath (io/file dir marker-name))
                                       (into-array OpenOption [StandardOpenOption/CREATE
                                                               StandardOpenOption/TRUNCATE_EXISTING
                                                               StandardOpenOption/WRITE
                                                               LinkOption/NOFOLLOW_LINKS])))
        (Files/move (.toPath dir) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
        1
        (catch Exception _ 0)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} cleanup-stale-events!
  "Prune stored events whose retention has run out. What goes depends on
   where it lives under the events directory:

     live/{wid}/      Nothing of a run that has a manifest (the JVM
                      runner's; its archive moves it) or that is still
                      written to: resume replays it. A manifest-less run
                      (a Babashka run, which never archives) idle for
                      the TTL is archived here when its newest lifecycle
                      event ends it (completed, failed or cancelled; a
                      resume after that is a new attempt read from
                      archived/), so the archived/ rules below apply to
                      it next; one running or crashed stays; if it
                      holds no event file, its directory goes. A run
                      with a symlinked, unreadable or undecodable file
                      stays, with one warning per process.
     archived/{wid}/  The whole directory, once its archive is older
                      than the TTL (by its `archived.marker`). One still
                      missing the marker is left for archive recovery.
     operator/        Each event file older than the TTL.
     {wid}/ (legacy)  The whole directory, once it and every file in it
                      are older than the TTL; never one that holds
                      anything but event files.

   An archived or legacy workflow a `retention-hold` keeps stays, however
   old: its checkpoint (say) still points at it.

   Anywhere in the tree, `.transit.tmp` files older than the TTL go too:
   a writer killed between its temp write and the rename leaves one, and
   no reader ever picks it up. No symlink below the events directory is
   followed, a symlinked archived/ or operator/ included; the events
   directory itself may be a symlink.

   Arguments:
     opts - Map with optional:
       :events-dir - Directory to clean (default: ~/.miniforge/events)
       :base-dir   - Alias for :events-dir
       :ttl-ms     - Retention in milliseconds (default: 7 days)

   Returns: Number of files deleted"
  [& [opts]]
  (let [ttl-ms (get opts :ttl-ms default-event-ttl-ms)
        events-dir (io/file (or (:events-dir opts) (:base-dir opts) (sinks/default-events-dir)))
        cutoff (- (System/currentTimeMillis) ttl-ms)
        stale? (fn [^java.io.File f suffix]
                 (and (.isFile f)
                      (str/ends-with? (.getName f) suffix)
                      (< (.lastModified f) cutoff)))
        ;; A registered `retention-hold` keeps the workflow; one that
        ;; throws (an ex-info included, whose thrown object try+ sees as
        ;; its data map) keeps it too: an unanswered question deletes
        ;; nothing.
        holds (remove #{:default} (keys (methods retention-hold)))
        nonfatal? (fn [thrown] (not (instance? Error thrown)))
        held? (fn [^java.io.File dir]
                (boolean (some (fn [hold]
                                 (try+ (retention-hold hold (.getName dir) events-dir)
                                       (catch nonfatal? _ true)))
                               holds)))]
    (if (.isDirectory events-dir)
      (let [reserved #{(layout/live-subdir) (layout/archived-subdir) (layout/operator-subdir)}
            ;; A symlinked archived/ or operator/ points outside the
            ;; events tree: never list or delete through it.
            real-subdir (fn [subdir]
                          (let [d (io/file events-dir subdir)]
                            (when-not (Files/isSymbolicLink (.toPath d)) d)))
            archived-root (real-subdir (layout/archived-subdir))
            operator-root (real-subdir (layout/operator-subdir))
            live-root     (real-subdir (layout/live-subdir))
            archives (when archived-root
                       (->> (child-dirs archived-root)
                            (filter #(archive-expired? % cutoff))
                            (remove held?)))
            legacy (->> (child-dirs events-dir)
                        (remove #(contains? reserved (.getName ^java.io.File %)))
                        (filter #(legacy-workflow-expired? % cutoff manifest-filename))
                        (remove held?))
            pruned (reduce + 0 (map #(delete-all! (reverse (walk-tree %)))
                                    (concat archives legacy)))
            operator (if operator-root
                       (->> (walk-tree operator-root)
                            (filter #(stale? % ".json"))
                            delete-all!)
                       0)
            temps (->> (walk-tree events-dir)
                       (filter #(stale? % ".transit.tmp"))
                       delete-all!)
            marker    (layout/archived-marker-filename)
            idle-live (when live-root
                        (filter #(idle-unmanaged-run? % cutoff manifest-filename marker)
                                (child-dirs live-root)))
            emptied (reduce + 0 (map #(delete-all! (reverse (walk-tree %)))
                                     (filter no-events? idle-live)))]
        (when archived-root
          (doseq [dir (remove no-events? idle-live)
                  :let [verdict (run-verdict dir)
                        key     (str dir)]]
            (cond
              (= :archive verdict) (archive-completed-run! dir archived-root marker)
              (and (string? verdict)
                   (not (contains? (first (swap-vals! warned-runs conj key)) key)))
              (binding [*out* *err*]
                (println (str "WARNING: event cleanup keeps " dir " in live/: " verdict))))))
        (+ pruned operator temps emptied))
      0)))
