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
(ns ai.miniforge.repo-index.search-lex
  "BM25 lexical search over repo file contents.

   Pure Clojure implementation — no JNI dependencies. Suitable for
   single-repo indexing (thousands of files, not millions).

   Layer 1 — depends on factory (Layer 0)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.miniforge.repo-index.factory :as factory]))

;------------------------------------------------------------------------------ Layer 0

;; Configuration (loaded from EDN resource)
(def ^{:stratum 0} ^:private config-path "config/repo-index/search.edn")

;; Tokenization
(defn- ^{:stratum 0} tokenize
  "Split text into lowercase word tokens."
  [text]
  (->> (str/split (str/lower-case text) #"[^a-z0-9_-]+")
       (remove str/blank?)))

;; Index construction
(defn- ^{:stratum 0} read-file-content
  "Read file content from disk. Returns nil for binary/unreadable files."
  [repo-root path]
  (try
    (let [f (io/file repo-root path)]
      (when (.isFile f)
        (slurp f)))
    (catch Exception _
      nil)))

(defn- ^{:stratum 0} build-inverted-index
  "Build an inverted index from document entries.
   Returns {:term->doc-ids {term #{doc-id ...}} :doc-freq {term count}}."
  [docs]
  (reduce
    (fn [acc {:keys [path term-freqs]}]
      (reduce-kv
        (fn [acc2 term _freq]
          (-> acc2
              (update-in [:term->doc-ids term] (fnil conj #{}) path)
              (update-in [:doc-freq term] (fnil inc 0))))
        acc
        term-freqs))
    (factory/->inverted-index)
    docs))

(defn- ^{:stratum 0} compute-avg-doc-length
  "Compute average document length from doc entries."
  [docs]
  (if (seq docs)
    (double (/ (reduce + 0 (map :token-count docs)) (count docs)))
    0.0))

;; BM25 scoring
(defn- ^{:stratum 0} idf
  "Compute inverse document frequency for a term."
  [doc-count doc-freq-for-term]
  (Math/log (+ 1.0 (/ (- doc-count doc-freq-for-term 0.5)
                       (+ doc-freq-for-term 0.5)))))

;; Context extraction
(defn- ^{:stratum 0} extract-snippet
  "Extract a snippet around a matching line with context."
  [lines line-idx context-lines]
  (let [start (max 0 (- line-idx context-lines))
        end (min (count lines) (+ line-idx context-lines 1))]
    (factory/->snippet (inc start) (inc (dec end))
                       (str/join "\n" (subvec lines start end)))))

(defn- ^{:stratum 0} find-matching-lines
  "Find line indices containing any query term."
  [content query-terms]
  (let [lines (str/split-lines content)
        lower-lines (mapv str/lower-case lines)]
    (->> (range (count lower-lines))
         (filter (fn [i]
                   (let [line (nth lower-lines i)]
                     (some #(str/includes? line %) query-terms))))
         vec)))

;; Candidate collection
(defn- ^{:stratum 0} collect-candidates
  "Collect all document paths matching any query term."
  [search-index query-terms]
  (reduce
    (fn [paths term]
      (into paths (get (:term->doc-ids search-index) term #{})))
    #{}
    query-terms))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} load-search-config []
  (if-let [res (io/resource config-path)]
    (get (edn/read-string (slurp res)) :repo-index/search {})
    {}))

(defn- ^{:stratum 1} build-doc-entry
  "Build a document entry for the inverted index."
  [repo-root {:keys [path]}]
  (when-let [content (read-file-content repo-root path)]
    (let [tokens (tokenize content)]
      (factory/->doc-entry path (count tokens) (frequencies tokens) content))))

(defn- ^{:stratum 1} build-snippets
  "Build preview snippets for matching lines in a document."
  [content query-terms context-lines max-hits]
  (let [lines (into [] (str/split-lines content))
        matching (find-matching-lines content query-terms)]
    (->> matching
         (take max-hits)
         (mapv #(extract-snippet lines % context-lines))
         (filterv #(not (str/blank? (:text %)))))))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} ^:private search-config (delay (load-search-config)))

(defn ^{:stratum 2} build-search-index
  "Build a BM25 search index from a repo index.

   Arguments:
   - repo-index - RepoIndex map from scanner/scan or build-index

   Returns:
   - SearchIndex map with inverted index, doc entries, and corpus stats"
  [repo-index]
  (let [repo-root (:repo-root repo-index)
        files (->> (:files repo-index) (remove :generated?))
        docs (->> files (keep (partial build-doc-entry repo-root)) vec)
        doc-map (into {} (map (fn [d] [(:path d) d]) docs))
        inv-idx (build-inverted-index docs)]
    (factory/->search-index doc-map (count docs)
                            (compute-avg-doc-length docs)
                            (:term->doc-ids inv-idx)
                            (:doc-freq inv-idx))))

;------------------------------------------------------------------------------ Layer 3

(defn- ^{:stratum 3} bm25-k1 [] (get @search-config :bm25-k1 1.2))

(defn- ^{:stratum 3} bm25-b  [] (get @search-config :bm25-b 0.75))

(defn- ^{:stratum 3} default-max-results [] (get @search-config :default-max-results 10))

(defn- ^{:stratum 3} default-context-lines [] (get @search-config :default-context-lines 3))

(defn- ^{:stratum 3} default-max-hits [] (get @search-config :default-max-hits 5))

;------------------------------------------------------------------------------ Layer 4

(defn- ^{:stratum 4} bm25-term-score
  "Compute BM25 score for a single term in a single document."
  [tf-in-doc doc-length avg-doc-length idf-value]
  (let [k1 (bm25-k1)
        b (bm25-b)
        numerator (* tf-in-doc (+ k1 1.0))
        denominator (+ tf-in-doc
                       (* k1 (+ (- 1.0 b) (* b (/ doc-length avg-doc-length)))))]
    (* idf-value (/ numerator denominator))))

;------------------------------------------------------------------------------ Layer 5

(defn- ^{:stratum 5} score-document
  "Score a document against query terms."
  [doc query-terms search-index]
  (let [{:keys [avg-doc-length doc-count doc-freq]} search-index
        {:keys [term-freqs token-count]} doc]
    (reduce
      (fn [score term]
        (let [tf (get term-freqs term 0)]
          (if (zero? tf)
            score
            (let [df (get doc-freq term 1)
                  idf-val (idf doc-count df)]
              (+ score (bm25-term-score tf token-count avg-doc-length idf-val))))))
      0.0
      query-terms)))

;------------------------------------------------------------------------------ Layer 6

(defn- ^{:stratum 6} score-and-rank
  "Score candidate documents and return top results."
  [search-index candidate-paths query-terms max-results]
  (->> candidate-paths
       (map (fn [path]
              (let [doc (get (:docs search-index) path)]
                {:path path
                 :score (score-document doc query-terms search-index)
                 :doc doc})))
       (sort-by :score >)
       (take max-results)))

;------------------------------------------------------------------------------ Layer 7

;; Public search API
(defn ^{:stratum 7} search
  "Search the index with a query string.

   Arguments:
   - search-index - SearchIndex from build-search-index
   - query        - Search query string
   - opts         - Optional map:
     - :max-results   - Maximum results to return (default from config)
     - :context-lines - Lines of context around hits (default from config)
     - :max-hits      - Max snippet hits per file (default from config)

   Returns:
   - Vector of SearchHit maps, sorted by BM25 score descending:
     [{:path :score :snippets [{:start-line :end-line :text}]}]"
  ([search-index query] (search search-index query {}))
  ([search-index query opts]
   (let [max-results (get opts :max-results (default-max-results))
         ctx-lines (get opts :context-lines (default-context-lines))
         max-hits (get opts :max-hits (default-max-hits))
         query-terms (tokenize query)
         candidates (collect-candidates search-index query-terms)
         scored (score-and-rank search-index candidates query-terms max-results)]
     (->> scored
          (mapv (fn [{:keys [path score doc]}]
                  (factory/->search-hit
                    path score
                    (build-snippets (:content doc) query-terms ctx-lines max-hits))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.repo-index.scanner :as scanner])

  (def idx (scanner/scan "."))
  (def si (build-search-index idx))

  (:doc-count si)
  (:avg-doc-length si)

  (def results (search si "implement phase interceptor"))
  (count results)
  (map :path results)
  (first results)

  :leave-this-here)
