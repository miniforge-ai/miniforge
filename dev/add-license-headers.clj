#!/usr/bin/env bb
;; Adds Apache 2.0 license headers to all .clj files that are missing them.
;; Safe to run multiple times — skips files that already have the header.

#_{:clj-kondo/ignore [:duplicate-require]}
(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(def header
  ";; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the \"License\");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an \"AS IS\" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.\n\n")

(defn needs-header? [file]
  (not (str/includes? (slurp file) "Licensed under the Apache License")))

(defn add-header! [file]
  (let [content (slurp file)]
    (spit file (str header content))))

(defn clj-files [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile %)
                     (str/ends-with? (.getName %) ".clj")
                     (not (str/includes? (.getPath %) "/.git/"))
                     (not (str/includes? (.getPath %) "/node_modules/"))))
       (sort-by #(.getPath %))))

(let [root (or (first *command-line-args*) ".")
      files (clj-files root)
      missing (filter needs-header? files)]
  (println (str "Total .clj files: " (count files)))
  (println (str "Missing headers:   " (count missing)))
  (doseq [f missing]
    (add-header! f)
    (println (str "  + " (.getPath f))))
  (println (str "\nDone. Added headers to " (count missing) " files.")))
