(ns ai.miniforge.knowledge.loader
  "Load rules and knowledge from .cursor/rules/ and project documentation.

   Converts .mdc rule files and markdown documentation into zettels
   and populates the knowledge store for agent access.

   This component is organized into 3 layers:
   - Layer 0: Pure path/file utilities
   - Layer 1: File loading operations
   - Layer 2: Orchestration (initialize function)"
  (:require
   [ai.miniforge.knowledge.store :as store]
   [ai.miniforge.knowledge.yaml :as yaml]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Pure path and file utilities

(defn find-mdc-files
  "Recursively find all .mdc files in a directory.
   Returns sequence of java.io.File objects."
  [dir]
  (when (.exists (io/file dir))
    (->> (file-seq (io/file dir))
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".mdc")))))

(defn extract-dewey-from-filename
  "Extract Dewey code from a single filename segment.
   Examples:
     '210-clojure.mdc'          -> '210'
     '001-stratified-design.mdc' -> '001'
     '000-foundations/'          -> '000'
     'readme.mdc'               -> nil"
  [filename]
  (when-let [match (re-find #"^(\d{3})-" filename)]
    (second match)))

(defn extract-dewey-from-path
  "Extract Dewey code from a relative path, preferring the directory-level
   Dewey code over the file-level code.

   Agent manifests use hundred-level prefixes (e.g. '200' for all language
   rules). Files within `200-languages/` have names like `210-clojure.mdc`,
   whose three-digit dewey is '210' — which does NOT start-with '200'.
   Using the directory dewey ('200') keeps manifest prefix matching correct.

   Examples:
     '200-languages/210-clojure.mdc'       -> '200'
     '000-foundations/010-simple-made-easy.mdc' -> '000'
     '700-workflows/710-git-branch.mdc'    -> '700'
     '210-clojure.mdc'                     -> '210'  (root file, no dir)"
  [relative-path]
  (let [parts (str/split relative-path #"/")]
    (if (> (count parts) 1)
      (extract-dewey-from-filename (first parts))
      (extract-dewey-from-filename (last parts)))))

(defn extract-tags-from-path
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

(defn generate-title-from-filename
  "Generate a human-readable title from a filename.
   Examples:
     '210-clojure.mdc' -> 'Clojure'
     'readme.mdc' -> 'Readme'"
  [filename]
  (-> filename
      (str/replace #"^\d{3}-" "")
      (str/replace #"\.mdc$" "")
      (str/replace #"-" " ")
      str/capitalize))

;------------------------------------------------------------------------------ Layer 1
;; File loading operations

(defn load-mdc-file
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

          ;; Extract metadata — use directory dewey so manifest hundred-level
          ;; prefixes ("200") match file-level codes ("210", "220", etc.)
          dewey (extract-dewey-from-path relative-path)
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
      (println "Error loading" (.getName file) ":" (.getMessage e))
      nil)))

(defn load-markdown-file
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

      {:zettel/id (random-uuid)
       :zettel/uid uid
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
       :zettel/created (java.util.Date.)
       :zettel/metadata {}})

    (catch Exception e
      (println "Error loading" (.getName file) ":" (.getMessage e))
      nil)))

(defn load-files-from-directory
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
        loader (fn [file] (load-mdc-file file (.getPath rules-root)))
        result (load-files-from-directory knowledge-store mdc-files loader)]
    (-> result
        (dissoc :items)
        (assoc :zettels (:items result)))))

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
        existing-files (filter #(.exists %) (map #(io/file root %) doc-files))
        loader (fn [file] (load-markdown-file file :hub))
        result (load-files-from-directory knowledge-store existing-files loader)]
    (-> result
        (assoc :files (mapv #(.getName %) (:items result)))
        (dissoc :items))))

;------------------------------------------------------------------------------ Layer 2
;; Orchestration

(defn load-rules
  "Load rules and return result with stats.
   Pure orchestration - delegates I/O to loader function."
  [knowledge-store rules-dir]
  (let [result (load-rules-from-directory knowledge-store rules-dir)]
    (select-keys result [:loaded :failed])))

(defn load-docs
  "Load documentation and return result with stats and file list.
   Pure orchestration - delegates I/O to loader function."
  [knowledge-store project-root]
  (let [result (load-project-docs knowledge-store project-root)]
    result))

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
  (load-rules-from-directory test-store ".cursor/rules")

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
