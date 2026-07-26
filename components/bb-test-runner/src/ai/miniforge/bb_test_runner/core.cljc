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
   testable under JVM Clojure; `run-all` uses a BB-only direct
   `babashka.classpath` require while this namespace remains
   JVM-loadable via reader conditionals.

   Layer 0: pure path and deps helpers.
   Layer 1: pure command/config derivation.
   Layer 2: Babashka/JVM runner entry points."
  (:require #?@(:bb [[babashka.classpath :as bb-classpath]])
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :as t])
  (:import
   [clojure.lang PersistentQueue]
   [java.lang Exception Long NumberFormatException String System]
   [java.nio.charset StandardCharsets]
   [java.nio.file Files]
   [java.util ArrayList Collection Collections Random]))

;------------------------------------------------------------------------------ Layer 0

;; Path and deps helpers (pure)
(def ^{:stratum 0} ^:private cloverage-version
  "1.2.4")

(def ^{:stratum 0} ^:private default-coverage-output
  "target/coverage")

(def ^{:stratum 0} ^:private stable-tag-glob-patterns
  "Supported stable-tag glob patterns.
   The repo has historical `stable-*` tags and a few older `stable/*`
   variants, so the wrapper treats both as stable baselines."
  ["stable-*" "stable/*"])

(def ^{:stratum 0} ^:private default-heartbeat-seconds
  30)

(def ^{:stratum 0} ^:private default-expand-start-size
  1)

(def ^{:stratum 0} ^:private git-worktree-env-keys
  ["GIT_INDEX_FILE" "GIT_DIR" "GIT_WORK_TREE" "GIT_COMMON_DIR"])

(def ^{:stratum 0} ^:private changed-or-affected-projects-argv
  ["clojure" "-M:poly" "ws" "get:changes:changed-or-affected-projects"
   "skip:dev" "color-mode:none"])

(defn- ^{:stratum 0} path-segments
  [path]
  (str/split path #"/"))

(defn- ^{:stratum 0} test-segment?
  [segment]
  (boolean (re-find #"^test($|-)" segment)))

(defn- ^{:stratum 0} resource-segment?
  [segment]
  (str/includes? segment "resources"))

(defn ^{:stratum 0} path->ns-symbol
  "Convert a `*_test.clj` file path, relative to a classpath `/test`
   root, into its namespace symbol. Strips `.clj`, converts `/` to `.`,
   and underscores to hyphens."
  [relative-path]
  (-> relative-path
      (str/replace #"\.clj$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))

(defn- ^{:stratum 0} normalized-alias-keys
  [{:keys [alias-key alias-keys]}]
  (vec (or alias-keys
           (when alias-key [alias-key])
           [:test])))

(defn ^{:stratum 0} stable-tags-present?
  "True when `tags` contains at least one recognized stable tag."
  [tags]
  (boolean
   (some (fn [tag]
           (let [normalized (some-> tag str str/trim not-empty)]
             (and normalized
                  (or (str/starts-with? normalized "stable-")
                      (str/starts-with? normalized "stable/")))))
         tags)))

(defn ^{:stratum 0} parse-project-selector
  "Parse a Polylith project selector or env value into a vector of
   project names.

   Accepted forms:
   - `project:proj1:proj2`
   - `proj1:proj2`
   - `proj1,proj2`"
  [selector]
  (let [raw (some-> selector str/trim not-empty)
        value (cond
                (nil? raw) nil
                (str/starts-with? raw "project:")
                (subs raw (count "project:"))
                :else raw)]
    (->> (some-> value (str/split #"[,:]"))
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn ^{:stratum 0} format-project-selector
  "Render an explicit Polylith project selector argument from project
   names."
  [projects]
  (when (seq projects)
    (str "project:" (str/join ":" projects))))

(defn- ^{:stratum 0} parse-error
  [code message data]
  {:ok? false
   :error {:code code
           :message message
           :data data}})

(defn- ^{:stratum 0} shuffle-projects
  [projects seed]
  (let [alist (ArrayList. ^Collection projects)]
    (Collections/shuffle alist (Random. (long (or seed 0))))
    (vec alist)))

(defn ^{:stratum 0} bisect-project-groups
  "Return contiguous project groups in breadth-first binary partition
   order."
  [projects]
  (let [root (vec projects)]
    (loop [queue (cond-> PersistentQueue/EMPTY
                   (> (count root) 1) (conj root))
           groups []]
      (if (empty? queue)
        groups
        (let [group (peek queue)
              queue-tail (pop queue)
              mid (quot (count group) 2)
              left (subvec group 0 mid)
              right (subvec group mid)
              next-groups (cond-> groups
                            (seq left) (conj left)
                            (seq right) (conj right))
              next-queue (cond-> queue-tail
                           (> (count left) 1) (conj left)
                           (> (count right) 1) (conj right))]
          (recur next-queue next-groups))))))

(defn ^{:stratum 0} load-deps-config
  "Read and parse a repo-local deps.edn file."
  [repo-root]
  (let [deps-path (str (fs/path repo-root "deps.edn"))]
    (edn/read-string
     (String. (Files/readAllBytes (.toPath (fs/file deps-path)))
              StandardCharsets/UTF_8))))

(defn- ^{:stratum 0} classpath-test-roots
  "Return the `/test` roots on the current Babashka classpath. Under
   JVM Clojure, `babashka.classpath` doesn't exist, so this throws an
   explicit unsupported-runtime error instead."
  []
  #?(:bb (->> (bb-classpath/split-classpath (bb-classpath/get-classpath))
              (filter #(str/ends-with? % "/test")))
     :default (throw (ex-info "Unsupported runtime: bb-test-runner run-all is only available under Babashka"
                              {:runtime :jvm
                               :namespace 'ai.miniforge.bb-test-runner.core}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} test-path?
  [path]
  (some test-segment? (path-segments path)))

(defn- ^{:stratum 1} resource-path?
  [path]
  (some resource-segment? (path-segments path)))

(defn ^{:stratum 1} discover-test-namespaces
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

(defn ^{:stratum 1} merge-deps-config
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

(defn ^{:stratum 1} stable-tag-globs
  "Return the stable-tag glob patterns recognized by Miniforge's
   stable-derived test scope."
  []
  stable-tag-glob-patterns)

(defn ^{:stratum 1} changed-projects-command
  "Return the argv that queries Polylith for the current changed-or-
   affected project set."
  []
  changed-or-affected-projects-argv)

(defn ^{:stratum 1} parse-project-list-output
  "Parse a Polylith `ws get:changes:changed-or-affected-projects`
   response into a vector of project names.

   Returns a vector on valid blank/sequential/set output, or
   `{:ok? false :error ...}` when the output is invalid or unparseable."
  [output]
  (let [trimmed (some-> output str/trim not-empty)]
    (if-not trimmed
      []
      (try
        (let [parsed (edn/read-string trimmed)]
          (cond
            (sequential? parsed) (mapv str parsed)
            (set? parsed) (->> parsed (map str) sort vec)
            :else (parse-error :bb-test-runner/invalid-project-list
                               "Expected a sequential or set project list."
                               {:output output
                                :parsed parsed})))
        (catch Exception e
          (parse-error :bb-test-runner/invalid-project-list
                       "Failed to parse project list output."
                       {:output output
                        :cause (.getMessage e)}))))))

(defn ^{:stratum 1} sanitize-git-worktree-env
  "Remove git worktree/index variables that must not leak into child
   processes.

   `git commit` and related flows can export worktree-specific git vars.
   If those leak into nested `git` calls inside tests, temp repos and
   temp worktrees stop behaving like standalone repos. The stable-derived
   wrapper must strip them before spawning `poly test`."
  [env]
  (apply dissoc env git-worktree-env-keys))

(defn ^{:stratum 1} heartbeat-seconds
  "Return the heartbeat interval, defaulting to 30 seconds.
   Invalid, missing, or non-positive values fall back to the default."
  [env]
  (let [raw (some-> (get env "MINIFORGE_TEST_HEARTBEAT_SECONDS")
                    str/trim
                    not-empty)]
    (if-not raw
      default-heartbeat-seconds
      (let [parsed (try
                     (Long/parseLong raw)
                     (catch NumberFormatException _
                       nil))]
        (if (pos-int? parsed)
          parsed
          default-heartbeat-seconds)))))

(defn- ^{:stratum 1} parse-long-arg
  [arg prefix]
  (let [raw (subs arg (count prefix))]
    (try
      {:ok? true :data (Long/parseLong raw)}
      (catch NumberFormatException ex
        (parse-error :bb-test-runner/invalid-diagnostic-arg
                     "Invalid stable-derived diagnostic argument."
                     {:arg arg
                      :prefix prefix
                      :expected-format (str prefix "N")
                      :cause (.getMessage ex)})))))

(defn ^{:stratum 1} order-projects
  "Apply diagnostic ordering controls to a project vector."
  [projects {:keys [direction order seed]}]
  (let [ordered (case order
                  :random (shuffle-projects projects seed)
                  (vec projects))]
    (case direction
      :back (vec (reverse ordered))
      ordered)))

(defn- ^{:stratum 1} positive-start-size
  [project-count requested-size]
  (-> (or requested-size default-expand-start-size)
      (max default-expand-start-size)
      (min project-count)))

(defn ^{:stratum 1} coverage-install-args
  "Build the JVM argv that prefetches the Cloverage tool dependency."
  []
  ["-P"
   "-Sdeps"
   (pr-str {:deps {'cloverage/cloverage {:mvn/version cloverage-version}}})])

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} classify-coverage-paths
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

(defn ^{:stratum 2} build-coverage-sdeps
  "Build an ad hoc deps map suitable for running Cloverage against the
   repo's test classpath."
  [deps-config opts]
  (let [{:keys [paths deps]} (merge-deps-config deps-config opts)]
    {:paths paths
     :deps (assoc deps
                  'cloverage/cloverage
                  {:mvn/version cloverage-version})}))

(defn ^{:stratum 2} changed-projects-since-stable-command
  "Return the argv that queries Polylith for changed-or-affected
   projects since the current stable tag anchor."
  []
  (let [argv (vec (changed-projects-command))
        marker "get:changes:changed-or-affected-projects"
        marker-index (.indexOf argv marker)]
    (if (neg? marker-index)
      (throw (ex-info "Invariant failed: changed-projects command is missing the Polylith change marker."
                      {:argv argv
                       :marker marker}))
      (let [insert-index (inc marker-index)]
        (vec (concat (subvec argv 0 insert-index)
                     ["since:stable"]
                     (subvec argv insert-index)))))))

(defn- ^{:stratum 2} assoc-long-arg
  [acc k arg prefix]
  (let [parsed (parse-long-arg arg prefix)]
    (if (:ok? parsed)
      (assoc acc k (:data parsed))
      (reduced parsed))))

(defn ^{:stratum 2} expand-project-groups
  "Return additive project groups that double in size until they cover
   the full ordered project set."
  [projects start-size]
  (let [project-vec (vec projects)
        project-count (count project-vec)]
    (if (zero? project-count)
      []
      (loop [group-size (positive-start-size project-count start-size)
             groups []]
        (let [group (subvec project-vec 0 group-size)
              next-groups (conj groups group)]
          (if (= group-size project-count)
            next-groups
            (recur (min project-count (* 2 group-size))
                   next-groups)))))))

;; Wiring — the actual task entry points
(defn ^{:stratum 2} run-all
  "Require every discovered test namespace, run `clojure.test`, and
   exit non-zero via `System/exit` on any failure or error.

   Babashka-only: relies on `babashka.classpath`. The function-local require is
   the intentional test discovery boundary; discovered test namespaces are data,
   not static product dependencies."
  []
  (let [files (discover-test-namespaces (classpath-test-roots))]
    (doseq [{:keys [ns]} files]
      (require ns))
    (let [{:keys [fail error]} (apply t/run-tests (map :ns files))]
      (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0)))))

(defn ^{:stratum 2} install-coverage-tool
  "Prefetch the Cloverage dependency into the repo's local Clojure cache."
  [{:keys [repo-root]}]
  (let [root (or repo-root ".")
        args (coverage-install-args)]
    (shell/with-sh-dir root
      (let [{:keys [exit out err]} (apply shell/sh "clojure" args)]
        (when-not (str/blank? out)
          (println out))
        (when-not (str/blank? err)
          (.println System/err err))
        exit))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} parse-diagnostic-args
  "Parse supported stable-derived diagnostic CLI arguments.

   Supported forms:
   - `mode:subset|expand|bisect`
   - `project:proj1:proj2`
   - `start-size:N`
   - `direction:front|back`
   - `order:declared|random`
   - `seed:N`"
  [args]
  (reduce
   (fn [acc arg]
     (cond
       (str/starts-with? arg "mode:")
       (assoc acc :mode (keyword (subs arg (count "mode:"))))

       (str/starts-with? arg "project:")
       (assoc acc :projects (parse-project-selector arg))

       (str/starts-with? arg "start-size:")
       (assoc-long-arg acc :start-size arg "start-size:")

       (str/starts-with? arg "direction:")
       (assoc acc :direction (keyword (subs arg (count "direction:"))))

       (str/starts-with? arg "order:")
       (assoc acc :order (keyword (subs arg (count "order:"))))

       (str/starts-with? arg "seed:")
       (assoc-long-arg acc :seed arg "seed:")

       :else acc))
   {}
   args))

(defn ^{:stratum 3} diagnostic-test-plan
  "Return a stable-derived diagnostic plan over an explicit project
   vector."
  [{:keys [mode projects start-size direction order seed]}]
  (let [effective-mode (or mode :subset)
        ordered-projects (order-projects (vec projects)
                                         {:direction direction
                                          :order order
                                          :seed seed})
        project-groups (if (empty? ordered-projects)
                         []
                         (case effective-mode
                           :expand (expand-project-groups ordered-projects start-size)
                           :bisect (bisect-project-groups ordered-projects)
                           [(vec ordered-projects)]))
        total-groups (count project-groups)]
    {:mode effective-mode
     :summary (str "Running "
                   (name effective-mode)
                   " diagnostics across "
                   (count ordered-projects)
                   " stable-derived projects.")
     :projects ordered-projects
     :steps (mapv (fn [index group]
                    (let [selector (format-project-selector group)]
                      {:label (str (name effective-mode)
                                 " project subset "
                                 (inc index) "/"
                                 total-groups
                                 " ("
                                 (count group)
                                 " projects)")
                       :argv (cond-> ["clojure" "-M:poly" "test"]
                               selector (conj selector))}))
                  (range)
                  project-groups)}))

;; Coverage command derivation (pure)
(defn ^{:stratum 3} coverage-args
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

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} run-coverage
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
          (.println System/err err))
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
