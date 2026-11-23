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
(ns ai.miniforge.knowledge.loader
  "Load rules and knowledge from .cursor/rules/ and project documentation.

   Converts .mdc rule files and markdown documentation into zettels
   and populates the knowledge store for agent access.

   - Layer 0: pure path/file utilities + directory-loading driver
   - Layer 1: load-mdc-file / load-project-docs (over Layer 0)
   - Layer 2: load-rules-from-directory / load-docs (over Layer 1)
   - Layer 3: load-rules (over Layer 2)
   - Layer 4: initialize-knowledge-store! (over Layer 3)

   5 real strata — over the rule 210 budget of 3; a genuine namespace
   split (Wave 2), not a labeling problem."
  (:require
   [ai.miniforge.knowledge.messages :as messages]
   [ai.miniforge.knowledge.store :as store]
   [ai.miniforge.knowledge.yaml :as yaml]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Pure path and file utilities
(defn ^{:stratum 0} find-mdc-files
  "Recursively find all .mdc files in a directory.
   Returns sequence of java.io.File objects."
  [dir]
  (when (.exists (io/file dir))
    (->> (file-seq (io/file dir))
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".mdc")))))

(defn ^{:stratum 0} extract-tags-from-path
  "Extract tags from file path relative to rules root.
   Uses slug-only path segments — no numeric prefix required.
   Examples:
     'foundations/stratified-design.mdc' -> [:foundations :stratified-design]
     'languages/clojure.mdc'             -> [:languages :clojure]
     'index.mdc'                         -> [:index]"
  [relative-path]
  (->> (str/split relative-path #"/")
       (map #(str/replace % #"\.mdc$" ""))
       (map keyword)
       vec))

(defn ^{:stratum 0} generate-title-from-filename
  "Generate a human-readable title from a slug filename.
   Examples:
     'clojure.mdc'          -> 'Clojure'
     'stratified-design.mdc' -> 'Stratified design'"
  [filename]
  (-> filename
      (str/replace #"\.mdc$" "")
      (str/replace #"-" " ")
      str/capitalize))

(defn ^{:stratum 0} load-markdown-file
  "Load a markdown file and convert to zettel.
   For project instruction files like AGENTS.md, CLAUDE.md, etc.
   `origin` is the file's path relative to the project root, with its
   real name; the uid is the origin without a trailing lowercase \".md\".
   Only that exact suffix is stripped, so files whose names differ only
   in the extension's case (CLAUDE.md, CLAUDE.MD) keep distinct uids."
  [file zettel-type origin]
  (try
    (let [content (slurp file)
          filename (.getName file)
          uid (str/replace origin #"\.md$" "")

          ;; Extract title from first # heading or use filename
          title (if-let [match (re-find #"^#\s+(.+)$" (first (str/split-lines content)))]
                  (second match)
                  (-> filename
                      (str/replace #"\.md$" "")
                      (str/replace #"-" " ")
                      str/capitalize))]

      {:zettel/id (random-uuid)
       :zettel/uid uid
       :zettel/title title
       :zettel/content content
       :zettel/type zettel-type
       :zettel/dewey "000"  ;; Foundational documentation
       :zettel/tags [:documentation :project]
       :zettel/links []
       :zettel/source {:type :migration
                      :origin origin
                      :confidence 1.0}
       :zettel/author "system"
       :zettel/created (java.util.Date.)
       :zettel/metadata {}})

    (catch Exception e
      (println (messages/t :loader/error-loading {:file (.getName file) :error (.getMessage e)}))
      nil)))

(def ^{:stratum 0} project-doc-paths
  "Project instruction files, each as path segments under the project root.
   Every segment matches case-insensitively, so AGENTS.md and agents.md
   both match on case-sensitive filesystems (Linux) as well as on
   case-insensitive ones (macOS default). .claude/CLAUDE.md is the
   project file Claude Code loads."
  [["agents.md"] ["claude.md"] [".clauderc"] ["claude_instructions.md"]
   [".claude" "claude.md"]])

(defn- ^{:stratum 0} children-named
  "Entries of `dir` whose name equals `entry-name`, ignoring case, sorted
   by name. Listing the directory yields each entry once under its real
   name, whatever the filesystem's case sensitivity. Empty when `dir` is
   not a readable directory."
  [dir ^String entry-name]
  (->> (.listFiles ^java.io.File (io/file dir))
       (filter #(.equalsIgnoreCase entry-name (.getName ^java.io.File %)))
       (sort-by #(.getName ^java.io.File %))))

(defn- ^{:stratum 0} descend-ignoring-case
  "Entries named `segment`, ignoring case, inside each of `dirs`."
  [dirs segment]
  (mapcat #(children-named % segment) dirs))

(defn- ^{:stratum 0} find-ignoring-case
  "Regular files under `root` at `segments`, each segment matched
   ignoring case."
  [root segments]
  (->> segments
       (reduce descend-ignoring-case [(io/file root)])
       (filter #(.isFile ^java.io.File %))))

(defn- ^{:stratum 0} same-file?
  "True when `a` and `b` are the same physical file, through symlinks
   and hard links, on every platform."
  [^java.io.File a ^java.io.File b]
  (java.nio.file.Files/isSameFile (.toPath a) (.toPath b)))

(defn- ^{:stratum 0} keep-unseen-file
  "Reducer step: adds `file` to `kept` unless a kept file is the same
   physical file. Pairwise, as there are only a handful of candidates."
  [kept file]
  (if (some #(same-file? % file) kept)
    kept
    (conj kept file)))

(defn- ^{:stratum 0} distinct-files
  "`files` without later entries that are the same physical file as an
   earlier one, e.g. a CLAUDE.md symlinked or hard-linked to AGENTS.md."
  [files]
  (reduce keep-unseen-file [] files))

(defn- ^{:stratum 0} relative-origin
  "`file`'s path relative to `root`, '/'-separated."
  [root ^java.io.File file]
  (-> (.relativize (.toPath ^java.io.File (io/file root)) (.toPath file))
      str
      (str/replace java.io.File/separator "/")))

(defn ^{:stratum 0} load-files-from-directory
  "Load files from directory using provided loader function.
   Returns {:loaded int :failed int :items [items...]}."
  [knowledge-store files loader-fn]
  (reduce
   (fn [acc file]
     (if-let [item (loader-fn file)]
       (do
         (store/put-zettel knowledge-store item)
         (-> acc
             (update :loaded inc)
             (update :items conj item)))
       (update acc :failed inc)))
   {:loaded 0 :failed 0 :items []}
   files))

;------------------------------------------------------------------------------ Layer 1

;; File loading operations
(defn ^{:stratum 1} load-mdc-file
  "Load a single .mdc file and convert to zettel data.
   Returns zettel map or nil if parsing fails."
  [file rules-root]
  (try
    (let [content (slurp file)
          filename (.getName file)
          relative-path (.replace (.getPath file) (str rules-root "/") "")

          ;; Parse frontmatter and body
          parsed (yaml/split-frontmatter content)
          frontmatter (when parsed
                       (yaml/parse-yaml-frontmatter (:frontmatter parsed)))
          body (if parsed
                (:body parsed)
                content)  ;; No frontmatter = entire file is body

          ;; Dewey from frontmatter is authoritative; path is never consulted
          dewey (get frontmatter :dewey)
          tags (extract-tags-from-path relative-path)
          uid (str/replace filename #"\.mdc$" "")
          title (or (:description frontmatter)
                   (:title frontmatter)
                   (generate-title-from-filename filename))]

      {:zettel/id (random-uuid)
       :zettel/uid uid
       :zettel/title title
       :zettel/content body
       :zettel/type :rule
       :zettel/dewey dewey
       :zettel/tags (vec (distinct (concat tags (:tags frontmatter []))))
       :zettel/links []
       :zettel/source {:type :migration
                      :origin relative-path
                      :confidence 1.0}
       :zettel/author "system"
       :zettel/created (java.util.Date.)
       :zettel/metadata (or frontmatter {})})

    (catch Exception e
      (println (messages/t :loader/error-loading {:file (.getName file) :error (.getMessage e)}))
      nil)))

(defn ^{:stratum 1} load-project-docs
  [knowledge-store project-root]
  (let [root (io/file project-root)
        doc-files (distinct-files
                   (mapcat #(find-ignoring-case root %) project-doc-paths))
        loader (fn [file]
                 (load-markdown-file file :hub (relative-origin root file)))
        result (load-files-from-directory knowledge-store doc-files loader)]
    (-> result
        ;; :items are the loaded zettels, not Files; each records its
        ;; root-relative path, with the file's real name, as its origin.
        (assoc :files (mapv #(get-in % [:zettel/source :origin]) (:items result)))
        (dissoc :items))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} load-rules-from-directory
  [knowledge-store rules-dir]
  (let [rules-root (io/file rules-dir)
        mdc-files (find-mdc-files rules-root)
        loader (fn [file] (load-mdc-file file (.getPath rules-root)))
        result (load-files-from-directory knowledge-store mdc-files loader)]
    (-> result
        (dissoc :items)
        (assoc :zettels (:items result)))))

(defn ^{:stratum 2} load-docs
  "Load documentation and return result with stats and file list.
   Pure orchestration - delegates I/O to loader function."
  [knowledge-store project-root]
  (let [result (load-project-docs knowledge-store project-root)]
    result))

;------------------------------------------------------------------------------ Layer 3

;; Orchestration
(defn ^{:stratum 3} load-rules
  "Load rules and return result with stats.
   Pure orchestration - delegates I/O to loader function."
  [knowledge-store rules-dir]
  (let [result (load-rules-from-directory knowledge-store rules-dir)]
    (select-keys result [:loaded :failed])))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} initialize-knowledge-store!
  "Initialize a knowledge store with rules and documentation.

   This is the main entry point for loading knowledge at system startup.

   Arguments:
   - knowledge-store - KnowledgeStore instance
   - options         - Optional configuration map with:
     :rules-dir       - Path to rules directory (default: \".cursor/rules\").
                        .standards/ is no longer the default — .mdc files there
                        are compiled to miniforge-standards.pack.edn via
                        `bb standards:pack` and loaded by agent-behavior at
                        runtime. Pass {:rules-dir \".standards\"} explicitly
                        if you still want direct MDC loading into the store.
     :project-root    - Path to project root (default: \".\")
     :skip-rules?     - Skip loading rules (default: false)
     :skip-docs?      - Skip loading docs (default: false)
     :on-progress     - Optional callback fn for progress updates
                        Called with {:phase :rules/:docs :event :start/:complete
                                   :loaded int :failed int :files [...]}

   Returns:
   - {:rules {:loaded int :failed int}
      :docs {:loaded int :failed int :files [...]}
      :total int}

   Example:
     (initialize-knowledge-store! store)
     (initialize-knowledge-store! store {:rules-dir \"custom/rules\"})
     (initialize-knowledge-store! store {:on-progress println})"
  ([knowledge-store]
   (initialize-knowledge-store! knowledge-store {}))

  ([knowledge-store opts]
   (let [rules-dir (get opts :rules-dir ".cursor/rules")
         project-root (get opts :project-root ".")
         skip-rules? (get opts :skip-rules? false)
         skip-docs? (get opts :skip-docs? false)
         on-progress (get opts :on-progress (constantly nil))

         ;; Load rules
         _ (when-not skip-rules?
             (on-progress {:phase :rules :event :start :dir rules-dir}))
         rules-result (if skip-rules?
                       {:loaded 0 :failed 0}
                       (load-rules knowledge-store rules-dir))
         _ (when-not skip-rules?
             (on-progress (assoc rules-result :phase :rules :event :complete)))

         ;; Load docs
         _ (when-not skip-docs?
             (on-progress {:phase :docs :event :start :dir project-root}))
         docs-result (if skip-docs?
                      {:loaded 0 :failed 0 :files []}
                      (load-docs knowledge-store project-root))
         _ (when-not skip-docs?
             (on-progress (assoc docs-result :phase :docs :event :complete)))

         total (+ (:loaded rules-result) (:loaded docs-result))]

     (on-progress {:phase :complete :total total})

     {:rules rules-result
      :docs docs-result
      :total total})))

(comment
  ;; Test loading rules
  (def test-store (store/create-store))

  ;; Load just rules
  (load-rules-from-directory test-store ".standards")

  ;; Load just docs
  (load-project-docs test-store ".")

  ;; Full initialization
  (initialize-knowledge-store! test-store)

  ;; Full initialization with progress callback
  (initialize-knowledge-store!
   test-store
   {:on-progress (fn [info]
                   (case (:phase info)
                     :rules (when (= :complete (:event info))
                             (println "Loaded" (:loaded info) "rules"))
                     :docs (when (= :complete (:event info))
                            (println "Loaded" (:loaded info) "docs:" (:files info)))
                     :complete (println "Total:" (:total info) "zettels")
                     nil))})

  ;; Check what was loaded
  (count (store/list-zettels test-store))

  ;; Query rules by Dewey
  (store/query test-store {:dewey-prefixes ["210"]})

  ;; Query by tags
  (store/query test-store {:tags [:clojure]})

  :end)
