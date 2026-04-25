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

   Layer 0: pure path and deps helpers.
   Layer 1: pure command/config derivation.
   Layer 2: Babashka/JVM runner entry points."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :as t]))

;------------------------------------------------------------------------------ Layer 0
;; Path and deps helpers (pure)

(def ^:private cloverage-version
  "1.2.4")

(def ^:private default-coverage-output
  "target/coverage")

(defn- path-segments
  [path]
  (str/split path #"/"))

(defn- test-segment?
  [segment]
  (boolean (re-find #"^test($|-)" segment)))

(defn- resource-segment?
  [segment]
  (str/includes? segment "resources"))

(defn- test-path?
  [path]
  (some test-segment? (path-segments path)))

(defn- resource-path?
  [path]
  (some resource-segment? (path-segments path)))

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

(defn- normalized-alias-keys
  [{:keys [alias-key alias-keys]}]
  (vec (or alias-keys
           (when alias-key [alias-key])
           [:test])))

(defn merge-deps-config
  "Merge the root deps.edn data with one or more alias keys into a
   runnable config map of `{:paths [...], :deps {...}}`."
  [deps-config opts]
  (let [alias-keys (normalized-alias-keys opts)
        alias-configs (map #(get-in deps-config [:aliases %] {}) alias-keys)
        alias-paths (mapcat #(get % :extra-paths []) alias-configs)
        alias-deps (apply merge (map #(get % :extra-deps {}) alias-configs))
        base-paths (get deps-config :paths [])
        base-deps (get deps-config :deps {})]
    {:paths (vec (concat base-paths alias-paths))
     :deps (merge base-deps alias-deps)}))

(defn classify-coverage-paths
  "Split merged classpath paths into source and test roots for
   Cloverage. Resource roots are excluded from instrumentation."
  [paths]
  (let [test-paths (->> paths
                        (filter test-path?)
                        vec)
        source-paths (->> paths
                          (remove test-path?)
                          (remove resource-path?)
                          vec)]
    {:source-paths source-paths
     :test-paths test-paths}))

(defn build-coverage-sdeps
  "Build an ad hoc deps map suitable for running Cloverage against the
   repo's test classpath."
  [deps-config opts]
  (let [{:keys [paths deps]} (merge-deps-config deps-config opts)]
    {:paths paths
     :deps (assoc deps
                  'cloverage/cloverage
                  {:mvn/version cloverage-version})}))

;------------------------------------------------------------------------------ Layer 1
;; Coverage command derivation (pure)

(defn coverage-args
  "Build the JVM command argv for a Cloverage run over the repo at the
   given deps config."
  [deps-config {:keys [output-dir fail-threshold] :as opts
                :or {output-dir default-coverage-output
                     fail-threshold 0}}]
  (let [sdeps (build-coverage-sdeps deps-config opts)
        {:keys [source-paths test-paths]}
        (classify-coverage-paths (get sdeps :paths))
        output-path (or output-dir default-coverage-output)
        source-args (mapcat #(vector "--src-ns-path" %) source-paths)
        test-args (mapcat #(vector "--test-ns-path" %) test-paths)]
    (vec (concat ["-Sdeps" (pr-str sdeps)
                  "-M"
                  "-m" "cloverage.coverage"
                  "--output" output-path
                  "--text"
                  "--html"
                  "--summary"
                  "--fail-threshold" (str fail-threshold)]
                 source-args
                 test-args))))

(defn load-deps-config
  "Read and parse a repo-local deps.edn file."
  [repo-root]
  (let [deps-path (str (fs/path repo-root "deps.edn"))]
    (edn/read-string (slurp deps-path))))

(defn coverage-install-args
  "Build the JVM argv that prefetches the Cloverage tool dependency."
  []
  ["-P"
   "-Sdeps"
   (pr-str {:deps {'cloverage/cloverage {:mvn/version cloverage-version}}})])

(defn- classpath-test-roots
  "Return the `/test` roots on the current Babashka classpath."
  []
  (let [get-classpath (requiring-resolve 'babashka.classpath/get-classpath)
        split-classpath (requiring-resolve 'babashka.classpath/split-classpath)]
    (->> (split-classpath (get-classpath))
         (filter #(str/ends-with? % "/test")))))

;------------------------------------------------------------------------------ Layer 2
;; Wiring — the actual task entry points

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

(defn install-coverage-tool
  "Prefetch the Cloverage dependency into the repo's local Clojure cache."
  [{:keys [repo-root]}]
  (let [root (or repo-root ".")
        args (coverage-install-args)]
    (shell/with-sh-dir root
      (let [{:keys [exit out err]} (apply shell/sh "clojure" args)]
        (when-not (str/blank? out)
          (println out))
        (when-not (str/blank? err)
          (binding [*out* *err*]
            (println err)))
        exit))))

(defn run-coverage
  "Run Cloverage for the repo rooted at `repo-root` using the selected
   deps.edn alias. Streams output and returns the process exit code."
  [{:keys [repo-root] :as opts}]
  (let [root (or repo-root ".")
        deps-config (load-deps-config root)
        args (coverage-args deps-config opts)]
    (shell/with-sh-dir root
      (let [{:keys [exit out err]} (apply shell/sh "clojure" args)]
        (when-not (str/blank? out)
          (println out))
        (when-not (str/blank? err)
          (binding [*out* *err*]
            (println err)))
        exit))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (path->ns-symbol "ai/miniforge/bb_paths/core_test.clj")
  (discover-test-namespaces ["test"])
  (classify-coverage-paths ["components/agent/src" "components/agent/resources" "components/agent/test"])
  (coverage-install-args)
  (coverage-args {:paths ["src" "test"]
                  :deps '{org.clojure/clojure {:mvn/version "1.12.0"}}
                  :aliases {:test {:extra-paths ["test"]}}}
                 {:alias-key :test})

  :leave-this-here)
