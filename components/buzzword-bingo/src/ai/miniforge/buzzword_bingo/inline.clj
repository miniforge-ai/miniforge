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
(ns ai.miniforge.buzzword-bingo.inline
  "Compile-time EDN inlining, so configuration survives the trip to
   ClojureScript, which has no runtime classpath. Expansion always runs
   on the JVM, for both targets.

   Build tools that cache macro expansion do not know this macro reads a file,
   so a consuming namespace belongs in shadow-cljs `:cache-blockers`."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

;; Resource reading
(defn- ^{:stratum 0} read-edn-resource [path]
  (if-let [url (io/resource path)]
    (edn/read-string (slurp url))
    (throw (IllegalArgumentException.
            (str "EDN resource not found on classpath: " path)))))

;------------------------------------------------------------------------------ Layer 1

;; Macro surface
(defmacro ^{:stratum 1} edn-resource
  "Emit the parsed EDN classpath resource at `path` as a literal."
  [path]
  (read-edn-resource path))

(comment
  (macroexpand '(edn-resource "config/buzzword-bingo/lexicon.edn")))
