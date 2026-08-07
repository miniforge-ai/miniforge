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
(ns ai.miniforge.codex.node
  "Codex node files -> node maps.

   The codex is a directory of markdown nodes with structured frontmatter,
   generated elsewhere (data-as-source, Codex SPEC T1 §8.1). This namespace
   only parses and loads; it never writes. An unreadable codex is data
   (:codex/anomaly), never an empty success."
  (:require [ai.miniforge.codex.messages :as msg]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private frontmatter-re
  ;; leading --- block; (?s) so .*? spans lines
  #"(?s)\A---\n(.*?)\n---\n(.*)\z")

(defn- ^{:stratum 0} title-of
  "First `# ` heading in the body, or nil."
  [body]
  (some->> (str/split-lines body)
           (some #(when (str/starts-with? % "# ") %))
           (#(subs % 2))
           str/trim))

(defn- ^{:stratum 0} parse-list-item
  "A frontmatter list item: `- value`, `- \"value\"`, or `- \"answer\": target`
   (the quoted form is how routes-to entries are emitted)."
  [line]
  (let [v (str/trim (subs (str/triml line) 1))]
    (if-let [[_ answer target] (re-matches #"^\"([^\"]*)\"\s*:\s*(.+)$" v)]
      {:answer answer :target (str/trim target)}
      {:value (str/replace v #"^\"|\"$" "")})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} parse-frontmatter
  "Parse the YAML-subset frontmatter the codex generators emit: top-level
   `key: value` lines and two-space-indented `- item` lists. Returns
   {:meta {...} :body \"...\"} or nil when the file has no frontmatter
   (index files)."
  [text]
  (when-let [[_ fm body] (re-matches frontmatter-re text)]
    (let [meta (reduce
                 (fn [{:keys [meta current]} line]
                   (cond
                     (and current (str/starts-with? line "  - "))
                     {:meta (update meta current (fnil conj []) (parse-list-item line))
                      :current current}

                     (and (not (str/starts-with? line " "))
                          (str/includes? line ":"))
                     (let [[k v] (str/split line #":" 2)
                           k (keyword (str/trim k))
                           v (str/trim v)]
                       (cond
                         ;; `key:` opens a list; following `  - ` lines append
                         (str/blank? v) {:meta (assoc meta k []) :current k}
                         ;; `key: []` is an inline empty list, not the string "[]"
                         (= v "[]")     {:meta (assoc meta k []) :current nil}
                         :else          {:meta (assoc meta k (str/replace v #"^\"|\"$" ""))
                                         :current nil}))

                     :else {:meta meta :current current}))
                 {:meta {} :current nil}
                 (str/split-lines fm))]
      {:meta (:meta meta) :body body})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} load-graph
  "Read every node under `codex-dir`/nodes/**. Returns
   {:nodes {id node-map}} or {:codex/anomaly :codex-unreadable ...} —
   including when a node file cannot be read mid-walk. IO failure is data
   here, never a throw: an exception escaping this boundary would crash the
   consumer instead of lowering its confidence."
  [codex-dir]
  (let [nodes-dir (io/file codex-dir "nodes")]
    (if-not (.isDirectory nodes-dir)
      {:codex/anomaly :codex-unreadable
       :codex/reason (msg/t :anomaly/no-nodes-dir {:dir codex-dir})}
      (try
        {:nodes (into {}
                      (comp (filter #(and (.isFile ^java.io.File %)
                                          (str/ends-with? (.getName ^java.io.File %) ".md")))
                            (keep (fn [f]
                                    (let [{:keys [meta body]} (parse-frontmatter (slurp f))]
                                      (when (:id meta)
                                        (assoc meta :title (title-of body))))))
                            (map (juxt :id identity)))
                      (file-seq nodes-dir))}
        ;; IOException + SecurityException, deliberately not Exception: these
        ;; are the failures a filesystem walk can produce, and a blanket
        ;; catch would also eat InterruptedException.
        (catch java.io.IOException e
          {:codex/anomaly :codex-unreadable
           :codex/reason (msg/t :anomaly/read-failed
                                {:dir (str nodes-dir) :error (ex-message e)})})
        (catch SecurityException e
          {:codex/anomaly :codex-unreadable
           :codex/reason (msg/t :anomaly/access-denied
                                {:dir (str nodes-dir) :error (ex-message e)})})))))
