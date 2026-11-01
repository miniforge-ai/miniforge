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
  "clj-kondo types from Malli function schemas.

   clj-kondo loads `.clj-kondo/metosin/malli-types-clj/config.edn` on its
   own and reports a call whose literal arguments, or whose use of the
   return value, contradict a function's declared schema. `emit!` writes
   that file from every brick source namespace that declares one
   (`:malli/schema` var metadata or `m/=>`); `check!` exits 1 when the
   committed file differs from what `emit!` would write, so CI fails on
   a schema change whose types were not regenerated."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [malli.clj-kondo :as mc]
   [malli.instrument :as mi]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} brick-roots
  "Directories whose bricks' `src` trees are scanned for function schemas."
  ["components" "bases"])

(def ^{:stratum 0} config-path
  "Where `malli.clj-kondo/save!` writes the :clj types."
  ".clj-kondo/metosin/malli-types-clj/config.edn")

(def ^{:stratum 0} declaration-re
  "Text that marks a source file as declaring a function schema."
  #":malli/schema|\(m/=>")

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
  "The clj-kondo config for the function schemas declared in `nss`."
  [nss]
  (apply require nss)
  (mi/collect! {:ns nss})
  (mc/linter-config (mapcat mc/collect nss)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} schema-namespaces
  "Every brick source namespace whose text declares a function schema."
  []
  (->> (for [root brick-roots
             brick (.listFiles (io/file root))
             f (file-seq (io/file brick "src"))
             :when (clojure-source? f)]
         f)
       (filter #(re-find declaration-re (slurp %)))
       (map ns-name-of)
       sort
       vec))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} emit!
  "Exec fn: write `config-path` from every namespace that declares a
   function schema."
  [_opts]
  (mc/save! (kondo-config (schema-namespaces)) :clj)
  (println "Wrote" config-path))

(defn ^{:stratum 2} check!
  "Exec fn: exit 1 when `config-path` is missing or differs from what
   `emit!` would write."
  [_opts]
  (let [f (io/file config-path)
        committed (when (.exists f) (edn/read-string (slurp f)))]
    (if (= committed (kondo-config (schema-namespaces)))
      (println config-path "is current")
      (do (println config-path "is stale; run `bb lint:malli-types` and commit it")
          (System/exit 1)))))
