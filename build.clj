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

(ns build
  "Build tooling for Miniforge Polylith workspace.

   Produces:
   - Babashka scripts (dev launchers with absolute paths)
   - Babashka uberscripts (single-file distribution)
   - JVM uberjars (server deployments)

   Usage:
     clj -T:build bb-script :project miniforge
     clj -T:build bb-uberscript :project miniforge
     clj -T:build uberjar :project miniforge-server
     clj -T:build clean"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as bp]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [clojure.tools.deps :as t]
   [clojure.tools.deps.util.dir :refer [with-dir]]))

;------------------------------------------------------------------------------ Layer 0
;; Pure functions, constants, and helpers with no I/O or state.
;; Depends only on Clojure core and external libs.

(def script-dir "dist")
(def class-dir "target/classes")

(defn today-commit-count
  "Count commits made today (for DateVer patch number)."
  []
  (let [today    (.format (java.time.LocalDate/now)
                          (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))
        {:keys [out]} (bp/sh "git" "rev-list" "--count" "--since" (str today "T00:00:00") "HEAD")]
    (parse-long (str/trim out))))

(defn version
  "Generate DateVer version string: YYYY.MM.DD.N
   Where N is the number of commits today (1-indexed for builds)."
  []
  (let [now   (java.time.LocalDate/now)
        year  (.getYear now)
        month (.getMonthValue now)
        day   (.getDayOfMonth now)
        n     (max 1 (today-commit-count))]  ; at least 1 for uncommitted builds
    (format "%d.%02d.%02d.%d" year month day n)))

(defn workspace-root
  "Return the workspace root directory."
  []
  (System/getProperty "user.dir"))

(defn project-root
  "Return the path to a project directory."
  [project]
  (str (workspace-root) "/projects/" project))

(defn project-exists?
  "Check if a project exists and has deps.edn."
  [project]
  (let [root (project-root project)]
    (and (fs/exists? root)
         (fs/exists? (str root "/deps.edn")))))

(defn get-project-aliases
  "Get aliases from a project's deps.edn (must be called with-dir)."
  []
  (let [edn-fn (juxt :root-edn :project-edn)]
    (-> (t/find-edn-maps)
        (edn-fn)
        (t/merge-edns)
        :aliases)))

(defn build-prompt-sexpr
  "Build S-expression for BB compatibility check."
  [main-ns]
  (if main-ns
    (format "(try (require '%s) (System/exit 0) (catch Throwable t (println t) (System/exit 1)))"
            main-ns)
    "(System/exit 0)"))

(defn lifted-basis
  "Create a basis where source deps have their primary external
   dependencies lifted to the top-level (needed for Polylith).
   Used for Babashka builds which handle classpath separately."
  []
  (let [default-libs  (:libs (b/create-basis))
        source-dep?   #(not (:mvn/version (get default-libs %)))
        lifted-deps   (reduce-kv
                       (fn [deps lib {:keys [dependents] :as coords}]
                         (if (and (contains? coords :mvn/version)
                                  (some source-dep? dependents))
                           (assoc deps lib (select-keys coords [:mvn/version :exclusions]))
                           deps))
                       {}
                       default-libs)]
    (-> (b/create-basis {:extra {:deps lifted-deps}})
        (update :libs #(into {} (filter (comp :mvn/version val)) %)))))

(defn jvm-basis
  "Create a full basis for JVM uberjar builds.
   Includes all source paths from local/root deps and all MVN libs.
   Unlike lifted-basis, this preserves Polylith source paths.
   proj-root: absolute path to the project directory."
  [proj-root]
  (let [proj-edn (edn/read-string (slurp (str proj-root "/deps.edn")))
        ;; Resolve :local/root deps relative to project dir
        resolved-deps (reduce-kv
                       (fn [m k v]
                         (if-let [lr (:local/root v)]
                           (let [abs (str (fs/absolutize (fs/path proj-root lr)))]
                             (assoc m k (assoc v :local/root abs)))
                           (assoc m k v)))
                       {}
                       (:deps proj-edn))]
    (b/create-basis {:project {:deps resolved-deps
                               :paths (or (:paths proj-edn) [])}})))

(defn source-dirs-from-basis
  "Extract source directories from a basis with Polylith local deps.
   Returns all :paths from local/root libs (component/base src dirs)."
  [basis]
  (->> (:libs basis)
       vals
       (filter :local/root)
       (mapcat :paths)
       (filter #(str/ends-with? % "/src"))
       vec))

(defn build-manifest
  "Generate JAR manifest map."
  [project]
  {"Git-Revision"          (str/trim-newline (:out (bp/sh "git" "rev-parse" "HEAD")))
   "Git-Tags"              (->> (:out (bp/sh "git" "tag" "--points-at" "HEAD"))
                                str/split-lines
                                (str/join " "))
   "Implementation-Title"  (str "miniforge-" project)
   "Implementation-Version" (version)
   "Build-Time"            (.format (java.time.ZonedDateTime/now)
                                    java.time.format.DateTimeFormatter/ISO_DATE_TIME)})

;------------------------------------------------------------------------------ Layer 1
;; I/O operations that depend on Layer 0.
;; File system access, process execution, validation.

(defn ensure-project!
  "Validate that a project exists, throw if not.
   Returns the project root path."
  [task project]
  (when-not (project-exists? project)
    (throw (ex-info (str task " requires a valid :project option")
                    {:project       project
                     :expected-path (project-root project)
                     :available     (vec (fs/list-dir "projects"))})))
  (project-root project))

(defn project-classpath-roots
  "Return the classpath roots for a project."
  [project]
  (let [root (ensure-project! "classpath" project)]
    (binding [b/*project-root* root]
      (:classpath-roots (b/create-basis {:aliases [:poly]})))))

(defn bb-compatible?
  "Return true if main-ns loads successfully in Babashka.
   Prints stack trace on failure for debugging."
  [main-ns cp-roots]
  (let [cp            (str/join ":" cp-roots)
        sexpr         (build-prompt-sexpr main-ns)
        {:keys [exit out err]} (bp/shell {:out :string :err :string}
                                         "bb" "--classpath" cp "-e" sexpr)]
    (when-not (zero? exit)
      (println "BB compatibility check failed:")
      (when (seq out) (println "stdout:" out))
      (when (seq err) (println "stderr:" err)))
    (zero? exit)))

(defn get-project-main
  "Get the :main namespace from a project's :uberjar alias."
  [project]
  (let [root (ensure-project! "main-ns" project)]
    (with-dir (io/file root)
      (-> (get-project-aliases) :uberjar :main))))

(defn changed-projects
  "Get list of projects that changed since a tag.
   Uses Polylith's change detection."
  [since]
  (let [basis    (b/create-basis {:aliases [:poly]})
        combined (t/combine-aliases basis [:poly])
        cmds     (b/java-command
                  {:basis     basis
                   :java-cmd  "java"
                   :java-opts (:jvm-opts combined)
                   :main      'clojure.main
                   :main-args (into (:main-opts combined)
                                    ["ws"
                                     "get:changes:changed-or-affected-projects"
                                     (str "since:" (case since
                                                     :before-tag "release"
                                                     :after-tag  "previous-release"))
                                     "skip:dev"
                                     "color-mode:none"])})
        {:keys [exit out err]} (b/process (assoc cmds :out :capture))]
    (when (seq err) (println err))
    (if (zero? exit)
      (edn/read-string out)
      (throw (ex-info "Unable to determine changed projects"
                      {:exit exit :out out :err err})))))

;------------------------------------------------------------------------------ Layer 2
;; Build tasks that compose Layer 0 and Layer 1.
;; These are the public entry points for clj -T:build.

(defn clean
  "Delete all build artifacts."
  [_]
  (println "🧹 Cleaning target/ and dist/...")
  (b/delete {:path "target"})
  (b/delete {:path "dist"})
  (println "✅ Clean complete"))

(defn bb-script
  "Generate a Babashka launcher script for development.
   Uses absolute paths - for local dev only, not distribution.

   Usage: clj -T:build bb-script :project miniforge"
  [{:keys [project]}]
  (let [_          (when-not project
                     (throw (ex-info "bb-script requires :project" {})))
        _          (ensure-project! "bb-script" project)
        main-ns    (get-project-main project)
        _          (when-not main-ns
                     (throw (ex-info "Missing :main in :uberjar alias"
                                     {:project project})))
        cp-roots   (project-classpath-roots project)
        _          (println "🔍 Checking Babashka compatibility...")
        _          (when-not (bb-compatible? main-ns cp-roots)
                     (throw (ex-info (str "❌ " project " cannot run in Babashka")
                                     {:project project :main-ns main-ns})))
        paths-edn  (->> cp-roots
                        (map #(str "\"" % "\""))
                        (str/join ", "))
        script     (str script-dir "/" project)]

    (fs/create-dirs script-dir)
    (spit script
          (format (str "#!/usr/bin/env bb\n"
                       ";; AUTO-GENERATED — miniforge project <%s>\n"
                       ";; For development only (uses absolute paths)\n\n"
                       "(require 'babashka.deps)\n"
                       "(babashka.deps/add-deps {:paths [%s]})\n\n"
                       "(require '%s)\n"
                       "(apply %s/-main *command-line-args*)\n")
                  project paths-edn main-ns main-ns))
    (fs/set-posix-file-permissions script "rwxr-xr-x")
    (println "✅ Babashka launcher:" script)))

(defn bb-uberscript
  "Generate a self-contained Babashka uberscript for distribution.
   Bundles all source into a single file.

   Note: Does not support dynamic requires. Use bb-uberjar for that.

   Usage: clj -T:build bb-uberscript :project miniforge"
  [{:keys [project]}]
  (let [_        (when-not project
                   (throw (ex-info "bb-uberscript requires :project" {})))
        _        (ensure-project! "bb-uberscript" project)
        main-ns  (get-project-main project)
        _        (when-not main-ns
                   (throw (ex-info "Missing :main in :uberjar alias"
                                   {:project project})))
        cp-roots (project-classpath-roots project)
        _        (println "🔍 Skipping Babashka compatibility check (runtime check only)...")
        output   (str script-dir "/" project)]

    (fs/create-dirs script-dir)
    (println "📦 Building uberscript...")

    ;; Use bb uberscript to bundle everything
    (let [{:keys [exit err out]}
          (bp/shell {:out :string :err :string}
                    "bb" "uberscript" output
                    "-cp" (str/join ":" cp-roots)
                    "-m" (str main-ns))]
      (when-not (zero? exit)
        (throw (ex-info "bb uberscript failed" {:exit exit :err err :out out}))))

    ;; Ensure shebang is present
    (let [content (slurp output)]
      (when-not (str/starts-with? content "#!/")
        (spit output (str "#!/usr/bin/env bb\n" content))))

    (fs/set-posix-file-permissions output "rwxr-xr-x")
    (println "✅ Uberscript:" output "(" (fs/size output) "bytes )")))

(defn bb-uberjar
  "Generate a Babashka uberjar for distribution.
   Supports dynamic requires and resources.

   Note: No compatibility check needed - bb uberjar itself will fail
   if the code isn't compatible with Babashka.

   Usage: clj -T:build bb-uberjar :project miniforge"
  [{:keys [project]}]
  (let [_        (when-not project
                   (throw (ex-info "bb-uberjar requires :project" {})))
        _        (ensure-project! "bb-uberjar" project)
        main-ns  (get-project-main project)
        _        (when-not main-ns
                   (throw (ex-info "Missing :main in :uberjar alias"
                                   {:project project})))
        cp-roots (project-classpath-roots project)
        output   (str script-dir "/" project ".jar")]

    (fs/create-dirs script-dir)
    (println "📦 Building uberjar...")

    ;; Use bb uberjar to bundle everything
    ;; This will fail on its own if the code isn't Babashka-compatible
    (let [{:keys [exit err out]}
          (bp/shell {:out :string :err :string}
                    "bb" "uberjar" output
                    "-cp" (str/join ":" cp-roots)
                    "-m" (str main-ns))]
      (when-not (zero? exit)
        (println "stdout:" out)
        (println "stderr:" err)
        (throw (ex-info "bb uberjar failed" {:exit exit :err err :out out}))))

    (println "✅ Uberjar:" output "(" (fs/size output) "bytes )")))

(defn uberjar
  "Build a JVM uberjar for a project.

   Usage: clj -T:build uberjar :project miniforge-server"
  [{:keys [project uber-file]}]
  (let [proj-root (ensure-project! "uberjar" project)
        main-ns   (get-project-main project)
        _        (when-not main-ns
                   (throw (ex-info "Missing :main in :uberjar alias"
                                   {:project project})))
        jar-file (or uber-file (str "target/" project ".jar"))
        manifest (build-manifest project)
        basis    (jvm-basis proj-root)]

    (println "📦 Building uberjar for" project "...")
    (b/delete {:path class-dir})

    ;; Compile Clojure — extract src dirs from local deps for Polylith
    (let [src-dirs (source-dirs-from-basis basis)]
      (println "  Compiling" (count src-dirs) "source directories...")
      (b/compile-clj {:basis      basis
                      :src-dirs   src-dirs
                      :class-dir  class-dir
                      :compile-opts {:direct-linking true}}))

    ;; Build uberjar
    (b/uber {:basis     basis
             :class-dir class-dir
             :uber-file jar-file
             :main      main-ns
             :manifest  manifest
             :exclude   ["LICENSE"
                         "META-INF/license/LICENSE.aix-netbsd.txt"
                         "META-INF/license/LICENSE.boringssl.txt"
                         "META-INF/license/LICENSE.mvn-wrapper.txt"
                         "META-INF/license/LICENSE.tomcat-native.txt"]})

    (b/delete {:path class-dir})

    ;; Write version file
    (fs/create-dirs (str proj-root "/target"))
    (spit (str proj-root "/target/version.edn")
          (pr-str {:version (version) :jar (fs/file-name jar-file)}))

    (println "✅ Uberjar:" jar-file)))

(defn compile-all
  "Compile all Clojure code to catch syntax errors.

   Usage: clj -T:build compile-all"
  [_]
  (println "🔨 Compiling all namespaces...")
  (b/delete {:path class-dir})
  (b/compile-clj {:basis     (b/create-basis {:aliases [:poly]})
                  :class-dir class-dir})
  (b/delete {:path class-dir})
  (println "✅ Compilation successful"))

(defn build-all
  "Build all changed projects.

   Usage: clj -T:build build-all"
  [{:keys [since] :or {since :before-tag}}]
  (let [projects (changed-projects since)]
    (if (empty? projects)
      (println "✅ No projects need building")
      (do
        (println "🏗️  Building" (count projects) "project(s):" projects)
        (doseq [p projects]
          (uberjar {:project p}))
        (println "✅ All builds complete")))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Clean build artifacts
  (clean nil)

  ;; Check if a project exists
  (project-exists? "miniforge")
  (project-exists? "nonexistent")

  ;; Get version
  (version)

  ;; Build a BB script for local dev
  (bb-script {:project "miniforge"})

  ;; Build a distributable uberscript
  (bb-uberscript {:project "miniforge"})

  ;; Build a JVM uberjar
  (uberjar {:project "miniforge-server"})

  ;; List changed projects
  (changed-projects :before-tag)

  ;; Build all changed projects
  (build-all {})

  :leave-this-here)
