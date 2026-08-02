(ns ai.miniforge.cursor-store.store
  "File-based cursor persistence.

   Stratification (intra-namespace):
   Layer 0 — no in-ns deps: cursor-file, normalize-for-storage,
             read-edn, write-edn.
   Layer 1 — public I/O (save-cursors, load-cursors). Both at L1 to
             keep the persistence API at one stratum."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [ai.miniforge.coerce.interface :as coerce]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.schema.interface :as schema])
  (:import [java.io StringWriter]))

;------------------------------------------------------------------------------ Layer 0

;; Pure helpers.
(defn- ^{:stratum 0} cursor-file
  "Derive cursor EDN file from a pipeline file path.
   <pipeline-dir>/.cursors/<pipeline-filename>"
  [pipeline-path]
  (let [f   (io/file pipeline-path)
        dir (or (.getParentFile f) (io/file "."))]
    (io/file dir ".cursors" (.getName f))))

(defn- ^{:stratum 0} normalize-for-storage
  "Re-key {stage-uuid → cursor-entry} to {[stage-name schema-name] → cursor-entry}.
   Entries missing stage-name or schema-name are dropped with a warning.

   The key has to survive between runs, which rules out both of the
   identifiers a cursor entry carries alongside them.
   `:stage/id` is regenerated per run. So is `:stage/connector-ref`:
   `pipeline-config`'s `instantiate-connectors` mints a fresh
   `UUID/randomUUID` per connector and the resolver substitutes it for
   the pack's symbolic `:conn/…` keyword, so a resolved stage's
   connector-ref is a different value on every run and a file keyed by
   it would never match on reload. `:stage/name` comes verbatim from
   the pipeline EDN — the same string an env config uses to key its
   per-stage overrides, so the pack format already depends on it being
   stable and unique within a pipeline."
  [logger cursor-map]
  (reduce-kv
   (fn [acc _id entry]
     (let [sname  (:stage/name entry)
           schema (:stage/schema-name entry)]
       (if (and sname schema)
         (assoc acc [sname schema] entry)
         (do (when logger
               (log/warn logger :cursor-store :cursor-store/entry-dropped
                         {:message "Cursor entry dropped — missing stage-name or schema-name"
                          :data {:stage/name (get entry :stage/name "unknown")}}))
             acc))))
   {}
   cursor-map))

(defn- ^{:stratum 0} read-edn
  "Parse an EDN string, normalizing instants exactly as the write side
   does.

   EDN's `#inst` reader produces a `java.util.Date`, so a cursor file
   containing `#inst \"…\"` loads a Date no matter how careful the
   writer was — a hand-edited file is enough, and `#inst` is the
   obvious thing for a human to type. Reading through the same
   normalizer makes the store's guarantee a property of the store
   rather than of whoever last wrote the file: a timestamp read out of
   here is an ISO-8601 string."
  [s]
  (coerce/stringify-instants (edn/read-string s)))

(defn- ^{:stratum 0} write-edn
  "Render a value to a pretty-printed EDN string, with every instant
   normalized to an ISO-8601 string first.

   Both halves of that matter. pprint emits
   `#object[java.time.Instant ...]` for an Instant, which is not
   readable EDN at all. A `java.util.Date` is the quieter problem: it
   pprints as `#inst` and reads back as a Date, so the file is valid
   and nothing fails — but `connector-http`'s `parse-timestamp`
   requires a string, returns nil for a Date, and `after-cursor?`
   then falls through to its no-watermark branch and re-ingests
   every record. Normalizing by type is what keeps a persisted
   cursor readable by the connector that has to honour it."
  [v]
  (let [sw (StringWriter.)]
    (pp/pprint (coerce/stringify-instants v) sw)
    (str sw)))

;------------------------------------------------------------------------------ Layer 1

;; Public I/O.
(defn ^{:stratum 1} save-cursors
  "Persist connector cursors to <pipeline-dir>/.cursors/<pipeline-filename>.
   cursor-map is the raw {stage-uuid → cursor-entry} map from
   :pipeline-run/connector-cursors, re-keyed for cross-run lookup.
   Returns schema/success or schema/failure."
  [logger pipeline-path cursor-map]
  (try
    (let [normalized (normalize-for-storage logger cursor-map)
          file       (cursor-file pipeline-path)]
      (if (empty? normalized)
        (do (when logger
              (log/info logger :cursor-store :cursor-store/no-cursors
                        {:message "No cursors to save — no ingest stages completed with cursors"}))
            (schema/success :cursors normalized))
        (let [dir (.getParentFile file)]
          (.mkdirs dir)
          (spit file (write-edn normalized))
          (when logger
            (log/info logger :cursor-store :cursor-store/saved
                      {:message (str "Saved " (count normalized) " cursor(s)")
                       :data {:count (count normalized) :path (str file)}}))
          (schema/success :cursors normalized))))
    (catch Exception e
      (when logger
        (log/error logger :cursor-store :cursor-store/write-failed
                   {:message (str "Failed to write cursor file: " (.getMessage e))
                    :data {:path pipeline-path :error (.getMessage e)}}))
      (schema/failure :cursors (.getMessage e)))))

(defn ^{:stratum 1} load-cursors
  "Load persisted cursors for a pipeline. Returns schema/success with
   :cursors key (map keyed by [stage-name schema-name]).
   Returns schema/success with {} on first run (no file yet).
   Returns schema/failure when a file that does exist cannot be read.
   The try covers the slurp as well as the parse, so a permissions or
   other I/O failure surfaces the same way a malformed file does —
   callers must not treat a read failure here as impossible."
  [logger pipeline-path]
  (try
    (let [file (cursor-file pipeline-path)]
      (if (.exists file)
        (let [cursors (read-edn (slurp file))]
          (when logger
            (log/info logger :cursor-store :cursor-store/loaded
                      {:message (str "Loaded " (count cursors) " cursor(s)")
                       :data {:count (count cursors) :path (str file)}}))
          (schema/success :cursors cursors))
        (do (when logger
              (log/info logger :cursor-store :cursor-store/first-run
                        {:message "No prior cursors found — starting fresh"}))
            (schema/success :cursors {}))))
    (catch Exception e
      (when logger
        (log/error logger :cursor-store :cursor-store/read-failed
                   {:message (str "Failed to read cursor file: " (.getMessage e))
                    :data {:path pipeline-path :error (.getMessage e)}}))
      (schema/failure :cursors (.getMessage e)))))
