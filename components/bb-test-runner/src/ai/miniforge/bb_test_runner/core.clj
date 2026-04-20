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

(ns ai.miniforge.bb-test-runner.core
  "Discover and run every `*_test.clj` file under each `/test` root on
   the Babashka classpath. The discovery helpers here are pure and
   testable under JVM Clojure; `run-all` calls into `babashka.classpath`
   lazily via `requiring-resolve` so this file stays JVM-loadable even
   though the runtime behaviour is Babashka-only.

   Layer 0: pure path-to-namespace conversion.
   Layer 1: pure test-file discovery on a provided root.
   Layer 2: Babashka-bound `run-all` that wires it all together."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]))

;------------------------------------------------------------------------------ Layer 0
;; Path → namespace symbol (pure)

(defn path->ns-symbol
  "Convert a `*_test.clj` file path, relative to a classpath `/test`
   root, into its namespace symbol. Strips `.clj`, converts `/` to `.`,
   and underscores to hyphens."
  [relative-path]
  (-> relative-path
      (str/replace #"\.clj$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))

;------------------------------------------------------------------------------ Layer 1
;; Test-file discovery (pure given a list of roots)

(defn discover-test-namespaces
  "Given a seq of `/test` roots, return a seq of `{:file :ns}` maps for
   every `*_test.clj` under each root."
  [roots]
  (mapcat (fn [root]
            (->> (fs/glob root "**_test.clj")
                 (map (fn [p]
                        {:file (str p)
                         :ns   (path->ns-symbol
                                (str (fs/relativize root p)))}))))
          roots))

(defn- classpath-test-roots
  "Return the `/test` roots on the current Babashka classpath."
  []
  (let [get-classpath (requiring-resolve 'babashka.classpath/get-classpath)
        split-classpath (requiring-resolve 'babashka.classpath/split-classpath)]
    (->> (split-classpath (get-classpath))
         (filter #(str/ends-with? % "/test")))))

;------------------------------------------------------------------------------ Layer 2
;; Wiring — the actual Babashka task entry point

(defn run-all
  "Require every discovered test namespace, run `clojure.test`, and
   exit non-zero via `System/exit` on any failure or error.

   Babashka-only: relies on `babashka.classpath`."
  []
  (let [files (discover-test-namespaces (classpath-test-roots))]
    (doseq [{:keys [ns]} files]
      (require ns))
    (let [{:keys [fail error]} (apply t/run-tests (map :ns files))]
      (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (path->ns-symbol "ai/miniforge/bb_paths/core_test.clj")
  (discover-test-namespaces ["test"])

  :leave-this-here)
