(ns ai.miniforge.data-foundry.cursor-store.store
  "File-based cursor persistence.

   Layer 0 — pure conversion helpers
   Layer 1 — file I/O (save-cursors, load-cursors)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.walk :as walk]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.schema.interface :as schema])
  (:import [java.io StringWriter]
           [java.time Instant]))

;;------------------------------------------------------------------------------ Layer 0 — pure helpers

(defn- cursor-file
  "Derive cursor EDN file from a pipeline file path.
   <pipeline-dir>/.cursors/<pipeline-filename>"
  [pipeline-path]
  (let [f   (io/file pipeline-path)
        dir (or (.getParentFile f) (io/file "."))]
    (io/file dir ".cursors" (.getName f))))

(defn- normalize-for-storage
  "Re-key {stage-uuid → cursor-entry} to {[connector-ref schema-name] → cursor-entry}.
   Entries missing connector-ref or schema-name are dropped with a warning."
  [logger cursor-map]
  (reduce-kv
   (fn [acc _id entry]
     (let [cref  (:stage/connector-ref entry)
           sname (:stage/schema-name entry)]
       (if (and cref sname)
         (assoc acc [cref sname] entry)
         (do (when logger
               (log/warn logger :cursor-store :cursor-store/entry-dropped
                         {:message "Cursor entry dropped — missing connector-ref or schema-name"
                          :data {:stage/name (or (:stage/name entry) "unknown")}}))
             acc))))
   {}
   cursor-map))

(defn- stringify-instants
  "Convert all java.time.Instant values to ISO-8601 strings.
   Clojure's pprint emits #object[java.time.Instant ...] which is not valid EDN;
   storing as a plain string keeps the file fully EDN-readable.
   :cursor/updated-at is audit metadata — it is not used in cursor lookup logic."
  [v]
  (walk/postwalk (fn [x] (if (instance? Instant x) (str x) x)) v))

(defn- write-edn
  "Render a value to a pretty-printed EDN string."
  [v]
  (let [sw (StringWriter.)]
    (pp/pprint (stringify-instants v) sw)
    (str sw)))

(defn- read-edn
  "Parse an EDN string."
  [s]
  (edn/read-string s))

;;------------------------------------------------------------------------------ Layer 1 — I/O

(defn save-cursors
  "Persist connector cursors to <pipeline-dir>/.cursors/<pipeline-filename>.
   cursor-map is the raw {stage-uuid → cursor-entry} map from
   :pipeline-run/connector-cursors. Returns schema/success or schema/failure."
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

(defn load-cursors
  "Load persisted cursors for a pipeline. Returns schema/success with
   :cursors key (map keyed by [connector-ref schema-name]).
   Returns schema/success with {} on first run (no file yet).
   Returns schema/failure only on parse errors."
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
