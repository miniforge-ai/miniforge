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
(ns ai.miniforge.malli-types
  "clj-kondo types from Malli function schemas. `emit!` writes the
   schemas declared in brick sources to `config-path`, which clj-kondo
   loads on its own; `check!` exits 1 when the committed file differs from
   what `emit!` would write."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [malli.clj-kondo :as mc]
   [malli.instrument :as mi]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} brick-roots
  "Directories whose bricks' `src` trees are scanned for function schemas."
  ["components" "bases"])

(def ^{:stratum 0} config-path
  "Where the types are written; clj-kondo auto-loads
   `.clj-kondo/<org>/<lib>/config.edn`. Fixed here, not left to
   `malli.clj-kondo/save!`, whose path differs between Malli versions."
  ".clj-kondo/metosin/malli-types-clj/config.edn")

(defn ^{:stratum 0} clojure-source?
  [^java.io.File f]
  (and (.isFile f) (boolean (re-find #"\.cljc?$" (.getName f)))))

(defn ^{:stratum 0} ns-name-of
  "The namespace symbol declared by the first form of `file`."
  [file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (second (read {:read-cond :allow} r)))))

(defn ^{:stratum 0} kondo-config
  "The clj-kondo config for the function schemas declared in `nss`; with
   no namespaces, the config that declares no types."
  [nss]
  (when (seq nss)
    (apply require nss)
    (mi/collect! {:ns nss}))
  (mc/linter-config (mapcat mc/collect nss)))

(defn ^{:stratum 0} write-config!
  "Write `config` to `path` as EDN, creating parent directories."
  [path config]
  (io/make-parents path)
  (spit path (with-out-str
               (binding [pprint/*print-right-margin* 100]
                 (pprint/pprint config)))))

(defn ^{:stratum 0} current?
  "True when the file at `path` exists and reads as `expected`."
  [path expected]
  (let [f (io/file path)]
    (and (.exists f)
         (= expected (edn/read-string (slurp f))))))

(defn ^{:stratum 0} declares-function-schema?
  "True when source `text` declares a function schema: the
   `:malli/schema` metadata key, or a call to `=>` under any alias
   (`m/=>`, `malli.core/=>`), with any spacing after the paren, or
   referred bare. A false match only costs a `require` of a namespace
   that registers nothing."
  [text]
  (boolean (re-find #":malli/schema|\(\s*(?:[^\s()/]+/)?=>\s" text)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} schema-namespaces
  "Every namespace in a brick `src` tree under `base` (default the working
   directory) whose text declares a function schema."
  ([] (schema-namespaces "."))
  ([base]
   (->> (for [root brick-roots
              brick (.listFiles (io/file base root))
              f (file-seq (io/file brick "src"))
              :when (clojure-source? f)]
          f)
        (filter #(declares-function-schema? (slurp %)))
        (map ns-name-of)
        sort
        vec)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} emit!
  "Exec fn: write `config-path` from every namespace that declares a
   function schema."
  [_opts]
  (write-config! config-path (kondo-config (schema-namespaces)))
  (println "Wrote" config-path))

(defn ^{:stratum 2} check!
  "Exec fn: exit 1 when `config-path` is missing or differs from what
   `emit!` would write."
  [_opts]
  (if (current? config-path (kondo-config (schema-namespaces)))
    (println config-path "is current")
    (do (println config-path "is stale; run `bb lint:malli-types` and commit it")
        (System/exit 1))))
