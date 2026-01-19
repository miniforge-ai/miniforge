;; Copyright (c) 2025 EngrammicAI
;; 
;; Permission is hereby granted, free of charge, to any person obtaining a copy
;; of this software and associated documentation files (the "Software"), to deal
;; in the Software without restriction, including without limitation the rights
;; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
;; copies of the Software, and to permit persons to whom the Software is
;; furnished to do so, subject to the following conditions:
;; 
;; The above copyright notice and this permission notice shall be included in all
;; copies or substantial portions of the Software.
;; 
;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
;; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
;; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
;; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
;; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
;; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
;; SOFTWARE.

(ns build
  "Builds Babashka scripts from Polylith components.
 
    Tasks:
    * script :project PROJECT [:script SCRIPT-NAME]
      - creates a Babashka script for the given project
    * all [:project PROJECT]
      - builds all scripts for a project or all projects
 
    For help, run:
      bb -f build.clj help
 
    Create script for development project:
      bb -f build.clj script :project development"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as bp]
   [build.javacp :as javacp]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [clojure.tools.deps :as t]
   [clojure.tools.deps.util.dir :refer [with-dir]]))

;; ------------------------------------------------------------------------------------------------- Defs
(def build-dir "target/scripts")
(def script-dir "dist")                 ; all bb launchers land here

(def class-dir "target/classes")
(def version (format "0.1.%s" (b/git-count-revs nil)))
(defn save-version-file [path jarfile]
  (spit (str path "version.edn") {:version version
                                  :jar (last (str/split jarfile #"/"))}))

;; ------------------------------------------------------------------------------------------------- Strata 0
;; Depends only on language features or external namespaces

(defn bb-compatible?
  "Return true if MAIN-NS (or the whole CP when nil) loads in Babashka.
   Prints Babashka's stack trace on failure so devs see the offender."
  [main-ns cp-roots]
  (let [cp                (str/join ":" cp-roots)                        ; no extra quotes
        sexpr             (if main-ns
                            (format "(try (require '%s) (System/exit 0) (catch Throwable t (println t) (System/exit 1)))"
                                    main-ns)
                            "(System/exit 0)")
        {:keys [exit]} (bp/shell
                        ["bb" "--classpath" cp "-e" sexpr]
                        {:shutdown false
                         :out      :string
                         :err      :string})]    ; wait & give exit code

    (zero? exit)))


(defn changed-projects
  "Produce the list of projects that need building.
   `since` should be `:before-tag` or `:after-tag`"
  [since]
  (let [basis    (b/create-basis {:aliases [:poly]})
        combined (t/combine-aliases basis [:poly])
        cmds     (b/java-command
                  {:basis     basis
                   :java-cmd  "java" ;; NOTE - unsure if we need to actually find the java bin path 
                   :java-opts (:jvm-opts combined)
                   :main      'clojure.main
                   :main-args (into (:main-opts combined)
                                    ["ws"
                                     "get:changes:changed-or-affected-projects"
                                     (str "since:"
                                          (case since
                                            :before-tag "release"
                                            :after-tag  "previous-release"))
                                     "skip:dev"
                                     "color-mode:none"])})
        {:keys [exit out err]}
        (b/process (assoc cmds :out :capture))]
    (when (seq err) (println err))
    (if (zero? exit)
      (edn/read-string out)
      (throw (ex-info "Unable to determine changed projects"
                      {:exit exit :out :out})))))


(defn clean
  "Delete the build directory"
  [_]
  (b/delete {:path "target"}))


(defn ensure-project-root
  "Given a task name and a project name, ensure the project
   exists and seems valid, and return the absolute path to it."
  [task project]
  (let [workspace-root (System/getProperty "user.dir")
        project-root (str workspace-root "/projects/" project)]
    (when-not (and project
                   (fs/exists? project-root)
                   (fs/exists? (str project-root "/deps.edn")))
      (throw (ex-info (str task " task requires a valid :project option")
                      {:project project
                       :workspace-root workspace-root
                       :expected-path project-root})))
    project-root))

;; POLYLITH nested deps.edn munging code
;;
(defn- get-project-aliases []
  (let [edn-fn (juxt :root-edn :project-edn)]
    (-> (t/find-edn-maps)
        (edn-fn)
        (t/merge-edns)
        :aliases)))


;; ------------------------------------------------------------------------------------------------- Strata 1
;; Depends on Strata 0

(defn project-classpath-roots
  "Return the class‑path roots (directories & jars) that Polylith
   resolved for the given **project**."
  [project]
  (let [project-root (ensure-project-root "bb-script" project)]
    (binding [b/*project-root* project-root]
      (let [basis (b/create-basis {:aliases [:poly]})]
        (:classpath-roots basis)))))

(defn- lifted-basis
  "This creates a basis where source deps have their primary
   external dependencies lifted to the top-level, such as is
   needed by Polylith and possibly other monorepo setups."
  []
  (let [default-libs (:libs (b/create-basis))
        source-dep? #(not (:mvn/version (get default-libs %)))
        lifted-deps
        (reduce-kv (fn [deps lib {:keys [dependents] :as coords}]
                     (if (and (contains? coords :mvn/version) (some source-dep? dependents))
                       (assoc deps lib (select-keys coords [:mvn/version :exclusions]))
                       deps))
                   {}
                   default-libs)]
    (-> (b/create-basis {:extra {:deps lifted-deps}})
        (update :libs #(into {} (filter (comp :mvn/version val)) %)))))


;; ------------------------------------------------------------------------------------------------- Strata 2
;; Depends on Strata 1

(defn compile-all
  "Compile every namespace so CI catches syntax errors even though
   the final artefact may run in Babashka."
  [_]
  (b/delete {:path class-dir})
  (b/compile-clj {:basis (b/create-basis {:aliases [:poly]})
                  :class-dir class-dir}))


;; ---------------------------------------------------------------- COVERAGE
;; Taken from a post in Clojure Slack from Sean Corefield.
(defn coverage
  "Invoked via exec-fn (-X). Accepts all the same options that Cloverage's
  `run-project` function accepts (and passes them through).

  Looks for all `src` and `test` paths on the classpath. For any `/test`
  path, assume there's an equivalent `/src` path because we tend to bring
  in source dependencies via `:local/root` as a project but we bring in
  test dependencies via `:extra-paths`. Pass the final set of source and
  test paths into Cloverage and let it run all the tests it can find."
  [{:keys [src-ns-path test-ns-path] :as options}]
  (let [paths   (when-not (and src-ns-path test-ns-path)
                  (into #{}
                        (comp (remove #(str/starts-with? % "/"))
                              (mapcat #(vector % (str/replace % #"/test$" "/src"))))
                        (str/split (System/getProperty "java.class.path") #":")))
        sources (or src-ns-path
                    (filter #(str/ends-with? % "/src")  paths))
        tests   (or test-ns-path
                    (filter #(str/ends-with? % "/test") paths))]
    ((requiring-resolve 'cloverage.coverage/run-project)
     (assoc options :src-ns-path sources :test-ns-path tests))))



(defn uberjar
  "Builds an uberjar for the specified project.
   Options:
   * :project - required, the name of the project to build,
   * :uber-file - optional, the path of the JAR file to build,
     relative to the project folder; can also be specified in
     the :uberjar alias in the project's deps.edn file; will
     default to target/PROJECT.jar if not specified.
   Returns:
   * the input opts with :class-dir, :compile-opts, :main, and :uber-file
     computed.
   The project's deps.edn file must contain an :uberjar alias
   which must contain at least :main, specifying the main ns
   (to compile and to invoke)."
  [{:keys [project uber-file] :as opts}]
  (let [project-root (ensure-project-root "uberjar" project)
        aliases      (with-dir (io/file project-root) (get-project-aliases))
        main         (-> aliases :uberjar :main)]
    (when-not main
      (throw (ex-info (str "the " project " project's deps.edn file does not specify the :main namespace in its :uberjar alias")
                      {:aliases aliases})))
    (binding [b/*project-root* project-root]
      (let [manifest {"Git-Revision" (str/trim-newline (:out (bp/process ["git" "rev-parse" "HEAD"])))
                      "Git-Tags" (->> (:out (bp/process ["git" "tag" "--points-at" "HEAD"]))
                                      (str/split-lines)
                                      (str/join " "))
                      "Implementation-Title" "Ixi"
                      "Implementation-Version" version
                      "Build-Time" (.format (java.time.ZonedDateTime/now) java.time.format.DateTimeFormatter/ISO_DATE_TIME)}
            ;; DOCKER BUG: Docker cannot deal with dynamic ENV or ARGs.
            ;; So stripping version to get deterministic filename
            ;; When building in docker
            jarfile-name (str "target/" project ".jar")
            uber-file (or uber-file
                          (-> aliases :uberjar :uber-file)
                          jarfile-name)
            basis (lifted-basis) ;;(b/create-basis {:project (str project-root "/deps.edn")})
            opts      (merge opts
                             {:class-dir    class-dir
                              :compile-opts {:direct-linking true}
                              :exclude ["LICENSE"
                                        "META-INF/license/LICENSE.aix-netbsd.txt"
                                        "META-INF/license/LICENSE.boringssl.txt"
                                        "META-INF/license/LICENSE.mvn-wrapper.txt"
                                        "META-INF/license/LICENSE.tomcat-native.txt"]
                              :main         main
                              :manifest     manifest
                              :uber-file    uber-file})]

        ;; Remove old class outputs
        (b/delete {:path class-dir})

        ;; Compile any nested Java source files
        (->> project-root
             (javacp/extract-java-src-paths :component)
             (javacp/compile-java basis class-dir))

        ;; build the uberjar
        (b/uber opts)
        (b/delete {:path class-dir})
        (save-version-file (str project-root "/target/") jarfile-name)
        (println "Uberjar: " jarfile-name " is built.")
        opts))))

(defn bb-script
  "Generates an executable Babashka launcher for :project.
   Usage:
     clj -T:build bb-script :project PROJECT
   Produces dist/PROJECT  (chmod 755)."
  [{:keys [project] :or {project "ops"}}]
  (let [project-root  (ensure-project-root "bb-script" project)
        ;; Main ns = same place you already declare for :uberjar
        main-ns       (-> (with-dir (io/file project-root)
                            (get-project-aliases))
                          :uberjar :main)
        _             (when-not main-ns
                        (throw (ex-info "Missing :main in :uberjar alias"
                                        {:project project})))
        cp-roots      (project-classpath-roots project)
        ;; quoted absolute paths for (babashka.deps/add-deps …)
        paths-edn     (->> cp-roots (map #(str "\"" % "\"")) (str/join ", "))
        script-path   (str script-dir "/" project)]

    (when-not (bb-compatible? main-ns cp-roots)
      (throw (ex-info (str "❌  " project " cannot run in Babashka – see stack trace above")
                      {:project project :main-ns main-ns})))


    (fs/create-dirs script-dir)
    (spit script-path
          (format (str "#!/usr/bin/env bb\n"
                       ";; AUTO‑GENERATED — Polylith project ‹%s›\n\n"
                       "(require 'babashka.deps)\n"
                       "(babashka.deps/add-deps {:paths [%s]})\n\n"
                       "(require '%s)\n"
                       "(apply %s/-main *command-line-args*)\n")
                  project paths-edn main-ns main-ns))
    (fs/set-posix-file-permissions script-path "rwxr-xr-x")
    (println "Babashka launcher written to" script-path)))

;; ------------------------------------------------------------------------------------------------- Strata 3
(defn- uberjars
  [{:keys [projects]}]
  (map #(uberjar %) projects))

;; ------------------------------------------------------------------------------------------------- Strata 4
(defn build-all-uberjars
  "Build uberjars for all changed artifacts."
  [params]
  (let [projects (-> (changed-projects (get params :since :before-tag))
                     (set))]
    (uberjars (assoc params :projects projects))))

;; ------------------------------------------------------------------------------------------------- Rich Comments
(comment
  (build-all-uberjars {:since "previous-release"})

  :leave-this-here)