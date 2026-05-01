#!/usr/bin/env bb
;; Audit miniforge .clj files for stratified-design violations within a
;; namespace. Reads `;-- Layer N` markers, builds the per-file call graph
;; via clj-kondo's analysis output, and reports any intra-namespace call
;; from a caller in layer A to a callee in a layer >= A (i.e. same-layer
;; or upward call).

(ns strata-audit
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- Layer 0
;; pure helpers, no in-namespace dependencies

;; The miniforge convention writes layer headers as
;;   `;------------------------------------------------------------------------------ Layer N`
;; or  `;; --- Layer N ---`. Both have at least one dash separating the
;; comment marker from the word `Layer`. The regex requires that — a
;; bare `;; Layer 1` mention in prose or a docstring will not be treated
;; as a section marker.
(def layer-marker-pattern #"(?i);+\s*-+\s*Layer\s+(\d+)\b")

(defn parse-layer-markers
  "Return [[line layer] …] for every `;------ Layer N` section header
   in `lines`. Match requires at least one dash between the comment
   marker and the word `Layer` so prose and docstring mentions are not
   picked up."
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [m (re-find layer-marker-pattern line)]
                 [(inc idx) (Long/parseLong (second m))])))
       vec))

(defn layer-for-row
  "Pick the layer of the most recent marker at or before `row`."
  [markers row]
  (->> markers
       (filter (fn [[m-row _]] (<= m-row row)))
       last
       second))

(defn- normalize-path
  "Return `path` with platform path separators flipped to forward
   slashes. Lets path-segment substring tests work uniformly on Unix
   and Windows."
  [^String path]
  (str/replace path "\\" "/"))

(defn read-clj-files
  "Walk `roots` and return every .clj/.cljc file path under */src trees.
   Path comparisons use forward slashes so the filter is portable to
   Windows (where `File/getPath` returns backslash-separated paths)."
  [roots]
  (->> roots
       (mapcat (fn [r] (file-seq (io/file r))))
       (filter #(.isFile ^java.io.File %))
       (filter (fn [f]
                 (let [n (.getName ^java.io.File f)]
                   (and (or (str/ends-with? n ".clj")
                            (str/ends-with? n ".cljc"))
                        (not (str/ends-with? n "_test.clj"))
                        (not (str/ends-with? n "_test.cljc"))))))
       (filter (fn [f]
                 (str/includes? (normalize-path (.getPath ^java.io.File f))
                                "/src/")))
       (map #(.getPath ^java.io.File %))))

;; ---------------------------------------------------------------- Layer 1
;; clj-kondo wrapper — depends only on Layer 0 helpers and built-ins

(defn- warn-analysis-failure!
  "Emit an explicit warning when clj-kondo analysis could not be
   produced. Goes to stderr so it doesn't pollute the EDN report on
   stdout."
  [paths message]
  (binding [*out* *err*]
    (println (str "WARNING: strata-audit could not analyse "
                  (count paths) " file(s): " message))
    (doseq [p (take 5 paths)]
      (println (str "  - " p)))
    (when (> (count paths) 5)
      (println (str "  … and " (- (count paths) 5) " more")))))

(defn analyse-paths
  "Run clj-kondo once over every path in `paths` and return the parsed
   analysis map (`:namespace-definitions`/`:var-definitions`/
   `:var-usages`).

   Bundles every file into a single kondo invocation rather than one
   process per file — typical 100×+ speed-up on a repo of this size.

   Uses `:skip-comments true` so var-definitions and var-usages inside
   `(comment …)` rich-comment blocks are excluded from the call graph;
   REPL-only experiments under `;;-- Rich Comment` are otherwise
   reported as production violations.

   On failure, emits a warning to stderr (so the EDN report stays
   parseable) and returns nil."
  [paths]
  (try
    (let [{:keys [out exit err]}
          (p/sh (into ["clj-kondo"
                       "--config"
                       "{:output {:analysis true :format :edn} :skip-comments true}"
                       "--lint"]
                      paths)
                {:out :string :err :string})]
      (cond
        (str/blank? out)
        (do (warn-analysis-failure!
             paths
             (str "kondo produced no analysis output (exit " exit
                  (when-not (str/blank? err)
                    (str " — " (str/trim err)))
                  ")"))
            nil)

        ;; kondo exits 2 when it found lint warnings/errors; exit 3 when
        ;; the analysis itself crashed. We trust the analysis output as
        ;; long as there is some.
        (#{0 2 3} exit)
        (-> out edn/read-string :analysis)

        :else
        (do (warn-analysis-failure!
             paths
             (str "kondo exited with status " exit
                  (when-not (str/blank? err)
                    (str " — " (str/trim err)))))
            nil)))
    (catch Exception e
      (warn-analysis-failure! paths (.getMessage e))
      nil)))

(defn- index-by-filename
  "Group items in `analysis-vec` by their `:filename` (relative path
   string from kondo)."
  [analysis-vec]
  (group-by :filename analysis-vec))

(defn file-analysis
  "Carve a per-file slice out of the bulk analysis map. Returns a map
   shaped like the single-file `(p/sh \"clj-kondo …\")` output for the
   downstream `file-violations` consumer."
  [bulk path]
  {:namespace-definitions (get (index-by-filename (:namespace-definitions bulk))
                               path [])
   :var-definitions       (get (index-by-filename (:var-definitions bulk))
                               path [])
   :var-usages            (get (index-by-filename (:var-usages bulk))
                               path [])})

(defn file-violations
  "Compute the violations for a single file, given its analysis map and
   the source lines. Returns a vector of violation maps."
  [path analysis lines]
  (let [markers     (parse-layer-markers lines)
        ns-name     (some-> analysis :namespace-definitions first :name)
        defs        (->> (:var-definitions analysis)
                         (filter #(= ns-name (:ns %))))
        def-layer   (into {}
                          (map (fn [{:keys [name row]}]
                                 [name (layer-for-row markers row)]))
                          defs)
        intra-calls (->> (:var-usages analysis)
                         (filter #(= ns-name (:to %)))
                         (filter :from-var))]
    (when (seq markers)
      (->> intra-calls
           (keep (fn [{:keys [from-var name row]}]
                   (let [caller-layer (def-layer from-var)
                         callee-layer (def-layer name)]
                     (when (and caller-layer callee-layer
                                (not= from-var name)
                                (>= callee-layer caller-layer))
                       {:file path
                        :ns ns-name
                        :caller from-var
                        :caller-layer caller-layer
                        :callee name
                        :callee-layer callee-layer
                        :row row}))))
           vec))))

;; ---------------------------------------------------------------- Layer 2
;; aggregation — composes Layer 1 (`file-violations`) over every file

(defn audit-tree
  [roots]
  (let [paths (vec (sort (read-clj-files roots)))
        bulk  (analyse-paths paths)]
    (->> paths
         (keep (fn [path]
                 (let [analysis (file-analysis bulk path)]
                   (when (seq (:namespace-definitions analysis))
                     (let [lines (str/split-lines (slurp path))
                           violations (file-violations path analysis lines)]
                       (when (seq violations)
                         {:file path
                          :violations violations
                          :count (count violations)})))))))))

(defn rank-by-violations
  [reports]
  (->> reports (sort-by (comp - :count)) vec))

;; ---------------------------------------------------------------- Layer 3
;; CLI entry point

(defn -main [& args]
  (let [roots (or (seq args) ["components" "bases"])
        reports (audit-tree roots)
        ranked (rank-by-violations reports)
        total-files (count reports)
        total-viols (apply + (map :count reports))]
    (println (format "Audited %d files with layer markers and violations." total-files))
    (println (format "Total violations: %d" total-viols))
    (println)
    (println "Top offenders (file → count):")
    (doseq [{:keys [file count]} (take 30 ranked)]
      (println (format "  %4d  %s" count file)))
    (println)
    (let [out-path ".miniforge/strata-violations.edn"]
      (.mkdirs (io/file ".miniforge"))
      (spit out-path (with-out-str (pp/pprint ranked)))
      (println (format "Saved full report to %s" out-path)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
