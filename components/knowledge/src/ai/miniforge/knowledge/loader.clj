(ns ai.miniforge.knowledge.loader
  "Load rules and knowledge from .cursor/rules/ and project documentation.

   Converts .mdc rule files and markdown documentation into zettels
   and populates the knowledge store for agent access."
  (:require
   [ai.miniforge.knowledge.store :as store]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0
;; File discovery and path utilities

(defn- find-mdc-files
  "Recursively find all .mdc files in a directory.
   Returns sequence of java.io.File objects."
  [dir]
  (when (.exists (io/file dir))
    (->> (file-seq (io/file dir))
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".mdc")))))

(defn- extract-dewey-from-filename
  "Extract Dewey code from filename.
   Examples:
     '210-clojure.mdc' -> '210'
     '001-stratified-design.mdc' -> '001'
     'readme.mdc' -> nil"
  [filename]
  (when-let [match (re-find #"^(\d{3})-" filename)]
    (second match)))

(defn- extract-tags-from-path
  "Extract tags from file path relative to rules root.
   Examples:
     '000-foundations/001-stratified-design.mdc' -> [:foundations :architecture]
     '200-languages/210-clojure.mdc' -> [:languages :clojure]
     '000-index.mdc' -> [:index]"
  [relative-path]
  (let [parts (str/split relative-path #"/")
        dir-part (when (> (count parts) 1) (first parts))
        file-part (last parts)

        ;; Extract tag from directory (e.g., "000-foundations" -> "foundations")
        dir-tag (when dir-part
                  (when-let [match (re-find #"^\d{3}-(.+)$" dir-part)]
                    (keyword (second match))))

        ;; Extract tag from filename (e.g., "210-clojure.mdc" -> "clojure")
        file-tag (when-let [match (re-find #"^\d{3}-(.+)\.mdc$" file-part)]
                   (keyword (second match)))]

    (vec (remove nil? [dir-tag file-tag]))))

;------------------------------------------------------------------------------ Layer 1
;; Markdown parsing (YAML frontmatter + body)

(defn- split-frontmatter
  "Split markdown content into frontmatter and body.
   Returns {:frontmatter string :body string} or nil if no frontmatter."
  [content]
  (let [lines (str/split-lines content)]
    (when (and (seq lines) (= "---" (first lines)))
      (let [end-idx (->> (rest lines)
                         (map-indexed vector)
                         (filter (fn [[_ line]] (= "---" line)))
                         first
                         first)]
        (when end-idx
          {:frontmatter (str/join "\n" (take end-idx (rest lines)))
           :body (str/join "\n" (drop (+ end-idx 2) lines))})))))

(defn- parse-yaml-frontmatter
  "Parse YAML frontmatter into EDN map.
   Simple parser for basic YAML - handles:
   - key: value
   - key: [list, items]
   - Lists with hyphens

   Note: This is a simplified YAML parser. For complex YAML,
   consider adding a proper YAML library."
  [yaml-str]
  (let [lines (str/split-lines yaml-str)
        result (atom {})]
    (doseq [line lines]
      (cond
        ;; key: value
        (re-find #"^(\w+):\s*(.+)$" line)
        (let [[_ k v] (re-find #"^(\w+):\s*(.+)$" line)]
          (swap! result assoc (keyword k)
                 (cond
                   ;; Array: [item1, item2]
                   (str/starts-with? v "[")
                   (try
                     (edn/read-string v)
                     (catch Exception _
                       ;; If EDN parsing fails, split by comma
                       (vec (map str/trim (str/split (str/replace v #"[\[\]]" "") #",")))))

                   ;; Boolean
                   (= v "true") true
                   (= v "false") false

                   ;; Number
                   (re-matches #"\d+" v)
                   (parse-long v)

                   ;; String (remove quotes if present)
                   :else
                   (str/replace v #"^[\"']|[\"']$" ""))))

        ;; List items (globs:)
        (and (str/starts-with? line "  - ")
             (not-empty @result))
        (let [k (last (keys @result))
              v (str/trim (subs line 4))]
          (swap! result update k
                 (fn [existing]
                   (cond
                     (vector? existing) (conj existing v)
                     (nil? existing) [v]
                     :else [existing v]))))))
    @result))

;------------------------------------------------------------------------------ Layer 2
;; Rule file loading

(defn- load-mdc-file
  "Load a single .mdc file and convert to zettel data.
   Returns zettel map or nil if parsing fails."
  [file rules-root]
  (try
    (let [content (slurp file)
          filename (.getName file)
          relative-path (.replace (.getPath file) (str rules-root "/") "")

          ;; Parse frontmatter and body
          parsed (split-frontmatter content)
          frontmatter (when parsed
                       (parse-yaml-frontmatter (:frontmatter parsed)))
          body (if parsed
                (:body parsed)
                content)  ;; No frontmatter = entire file is body

          ;; Extract metadata
          dewey (extract-dewey-from-filename filename)
          tags (extract-tags-from-path relative-path)
          uid (str/replace filename #"\.mdc$" "")
          title (or (:description frontmatter)
                   (:title frontmatter)
                   ;; Generate title from filename
                   (-> filename
                       (str/replace #"^\d{3}-" "")
                       (str/replace #"\.mdc$" "")
                       (str/replace #"-" " ")
                       str/capitalize))]

      {:zettel/uid uid
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
       :zettel/metadata (or frontmatter {})})

    (catch Exception e
      (println "Error loading" (.getName file) ":" (.getMessage e))
      nil)))

(defn load-rules-from-directory
  "Load all .mdc rule files from a directory into the knowledge store.

   Arguments:
   - knowledge-store - KnowledgeStore instance
   - rules-dir       - Path to .cursor/rules directory (string or File)

   Returns:
   - {:loaded int :failed int :zettels [zettel...]}

   Example:
     (load-rules-from-directory store \".cursor/rules\")"
  [knowledge-store rules-dir]
  (let [rules-root (io/file rules-dir)
        mdc-files (find-mdc-files rules-root)
        results (atom {:loaded 0 :failed 0 :zettels []})]

    (doseq [file mdc-files]
      (if-let [zettel-data (load-mdc-file file (.getPath rules-root))]
        (do
          (store/put-zettel knowledge-store zettel-data)
          (swap! results update :loaded inc)
          (swap! results update :zettels conj zettel-data))
        (swap! results update :failed inc)))

    @results))

;------------------------------------------------------------------------------ Layer 3
;; Project documentation loading

(defn- load-markdown-file
  "Load a markdown file and convert to zettel.
   For files like agents.md, claude.md, etc."
  [file zettel-type]
  (try
    (let [content (slurp file)
          filename (.getName file)
          uid (str/replace filename #"\.md$" "")

          ;; Extract title from first # heading or use filename
          title (if-let [match (re-find #"^#\s+(.+)$" (first (str/split-lines content)))]
                  (second match)
                  (-> filename
                      (str/replace #"\.md$" "")
                      (str/replace #"-" " ")
                      str/capitalize))]

      {:zettel/uid uid
       :zettel/title title
       :zettel/content content
       :zettel/type zettel-type
       :zettel/dewey "000"  ;; Foundational documentation
       :zettel/tags [:documentation :project]
       :zettel/links []
       :zettel/source {:type :migration
                      :origin filename
                      :confidence 1.0}
       :zettel/author "system"
       :zettel/metadata {}})

    (catch Exception e
      (println "Error loading" (.getName file) ":" (.getMessage e))
      nil)))

(defn load-project-docs
  "Load project documentation files (agents.md, claude.md, etc.) into knowledge store.

   Arguments:
   - knowledge-store - KnowledgeStore instance
   - project-root    - Path to project root directory (string or File)

   Returns:
   - {:loaded int :failed int :files [string...]}

   Example:
     (load-project-docs store \".\")"
  [knowledge-store project-root]
  (let [root (io/file project-root)
        doc-files ["agents.md" "claude.md" ".clauderc" "claude_instructions.md"]
        results (atom {:loaded 0 :failed 0 :files []})]

    (doseq [filename doc-files]
      (let [file (io/file root filename)]
        (when (.exists file)
          (if-let [zettel-data (load-markdown-file file :hub)]
            (do
              (store/put-zettel knowledge-store zettel-data)
              (swap! results update :loaded inc)
              (swap! results update :files conj filename))
            (swap! results update :failed inc)))))

    @results))

;------------------------------------------------------------------------------ Layer 4
;; Complete initialization

(defn initialize-knowledge-store!
  "Initialize a knowledge store with rules and documentation.

   This is the main entry point for loading knowledge at system startup.

   Arguments:
   - knowledge-store - KnowledgeStore instance
   - options         - Optional configuration map with:
     :rules-dir       - Path to rules directory (default: \".cursor/rules\")
     :project-root    - Path to project root (default: \".\")
     :skip-rules?     - Skip loading rules (default: false)
     :skip-docs?      - Skip loading docs (default: false)

   Returns:
   - {:rules {:loaded int :failed int}
      :docs {:loaded int :failed int}
      :total int}

   Example:
     (initialize-knowledge-store! store)
     (initialize-knowledge-store! store {:rules-dir \"custom/rules\"})"
  ([knowledge-store]
   (initialize-knowledge-store! knowledge-store {}))

  ([knowledge-store opts]
   (let [rules-dir (get opts :rules-dir ".cursor/rules")
         project-root (get opts :project-root ".")
         skip-rules? (get opts :skip-rules? false)
         skip-docs? (get opts :skip-docs? false)

         ;; Load rules
         rules-result (if skip-rules?
                       {:loaded 0 :failed 0}
                       (do
                         (println "📚 Loading rules from" rules-dir "...")
                         (let [result (load-rules-from-directory knowledge-store rules-dir)]
                           (println "  ✅ Loaded" (:loaded result) "rules")
                           (when (pos? (:failed result))
                             (println "  ⚠️  Failed to load" (:failed result) "rules"))
                           (select-keys result [:loaded :failed]))))

         ;; Load docs
         docs-result (if skip-docs?
                      {:loaded 0 :failed 0}
                      (do
                        (println "📖 Loading project documentation from" project-root "...")
                        (let [result (load-project-docs knowledge-store project-root)]
                          (when (pos? (:loaded result))
                            (println "  ✅ Loaded" (:loaded result) "documentation files:" (str/join ", " (:files result))))
                          (when (zero? (:loaded result))
                            (println "  ℹ️  No documentation files found"))
                          (select-keys result [:loaded :failed]))))

         total (+ (:loaded rules-result) (:loaded docs-result))]

     (println "🎉 Knowledge store initialized with" total "zettels")

     {:rules rules-result
      :docs docs-result
      :total total})))

(comment
  ;; Test loading rules
  (def test-store (store/create-store))

  ;; Load just rules
  (load-rules-from-directory test-store ".cursor/rules")

  ;; Load just docs
  (load-project-docs test-store ".")

  ;; Full initialization
  (initialize-knowledge-store! test-store)

  ;; Check what was loaded
  (count (store/list-zettels test-store))

  ;; Query rules by Dewey
  (store/query test-store {:dewey-prefixes ["210"]})

  ;; Query by tags
  (store/query test-store {:tags [:clojure]})

  :end)
