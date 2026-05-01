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

(def layer-marker-pattern #"(?i);+\s*(?:-+\s*)?Layer\s+(\d+)")

(defn parse-layer-markers
  "Return [[line layer] …] for every `;-- Layer N` comment in `lines`."
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

(defn read-clj-files
  "Walk `roots` and return every .clj/.cljc file path under */src trees."
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
                 (let [p (.getPath ^java.io.File f)]
                   (str/includes? p "/src/"))))
       (map #(.getPath ^java.io.File %))))

;; ---------------------------------------------------------------- Layer 1
;; clj-kondo wrapper — depends only on Layer 0 helpers and built-ins

(defn analyse-file
  "Run clj-kondo on `path` and return the parsed `:analysis` map (or nil
   if kondo blew up).

   Uses `:skip-comments true` so var-definitions and var-usages inside
   `(comment …)` rich-comment blocks are excluded from the call graph.
   REPL-only experiments under `;;-- Rich Comment` are otherwise
   reported as production violations."
  [path]
  (try
    (let [{:keys [out exit]}
          (p/sh ["clj-kondo" "--lint" path
                 "--config" "{:output {:analysis true :format :edn} :skip-comments true}"]
                {:out :string})]
      (when (and (zero? exit) (not (str/blank? out)))
        (-> out edn/read-string :analysis)))
    (catch Exception _ nil)))

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
  (->> (read-clj-files roots)
       (sort)
       (keep (fn [path]
               (when-let [analysis (analyse-file path)]
                 (let [lines (str/split-lines (slurp path))
                       violations (file-violations path analysis lines)]
                   (when (seq violations)
                     {:file path
                      :violations violations
                      :count (count violations)})))))))

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
