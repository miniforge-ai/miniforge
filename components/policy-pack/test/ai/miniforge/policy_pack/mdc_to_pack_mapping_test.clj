(ns ai.miniforge.policy-pack.mdc-to-pack-mapping-test
  "Acceptance tests for the MDC-to-Pack Rule Field Mapping design.

   These tests verify the mapping specification in
   work/designs/mdc-to-pack-field-mapping.edn is complete, internally
   consistent, and produces correct compiled rules per the worked examples.

   The tests implement the core transformation functions described in the spec
   and validate them against the spec's own examples, edge cases, and the
   actual .standards/ file inventory.

   Once the ETL task (bb standards:pack) is implemented, these tests serve
   as the authoritative acceptance suite."
  (:require
   [clojure.test :refer [deftest testing is are]]
   [clojure.string :as str]
   [clojure.set :as set]))

;; ---------------------------------------------------------------------------
;; Layer 0 — Test helpers: Pure functions implementing the mapping spec
;; ---------------------------------------------------------------------------

(defn slug-from-filename
  "Strip .mdc extension from filename (not full path).
   'foundations/stratified-design.mdc' → 'stratified-design'"
  [filepath]
  (let [filename (last (str/split filepath #"/"))]
    (str/replace filename #"\.mdc$" "")))

(defn rule-id-from-filepath
  "Derive :rule/id from .mdc filepath.
   Prefix slug with :std/ namespace.
   'foundations/stratified-design.mdc' → :std/stratified-design"
  [filepath]
  (keyword "std" (slug-from-filename filepath)))

(defn title-from-slug
  "Derive title from slug: hyphens → spaces, title-case each word.
   'pre-commit-discipline' → 'Pre Commit Discipline'"
  [slug]
  (->> (str/split slug #"-")
       (map str/capitalize)
       (str/join " ")))

(defn derive-title
  "Get :rule/title from frontmatter description or fallback to slug."
  [frontmatter filepath]
  (let [desc (get frontmatter "description")]
    (if (and desc (not (str/blank? desc)))
      desc
      (title-from-slug (slug-from-filename filepath)))))

(defn derive-description
  "Generate :rule/description from category and title.
   Format: 'Engineering standard (<category>): <title>'"
  [category title]
  (str "Engineering standard (" category "): " title))

(defn parse-dewey
  "Parse dewey string to integer. Returns -1 on failure (outside all ranges)."
  [dewey-str]
  (try
    (Integer/parseInt (str dewey-str))
    (catch Exception _ -1)))

(def dewey-range-to-phases
  "Dewey range → phase set mapping from Section 2 of the design."
  [[0 99]    #{:plan :implement :review :verify :release}
   [100 199] #{:implement :review}
   [200 299] #{:implement :review}
   [300 399] #{:plan :implement :review}
   [400 499] #{:implement :verify}
   [500 599] #{:implement :review}
   [600 699] #{:implement :review}
   [700 799] #{:plan :implement :review :verify :release}
   [800 899] #{:implement :review}
   [900 999] #{}])

(def dewey-ranges
  "Parsed vector of [low high phases] triples."
  (->> (partition 2 dewey-range-to-phases)
       (mapv (fn [[[lo hi] phases]] [lo hi phases]))))

(def default-phases #{:implement :review})

(defn dewey-to-phases
  "Look up phases for a dewey code (string). Falls back to default."
  [dewey-str]
  (let [code (parse-dewey dewey-str)]
    (or (some (fn [[lo hi phases]]
                (when (<= lo code hi) phases))
              dewey-ranges)
        default-phases)))

(defn normalize-globs
  "Wrap string globs in a vector; pass vectors through; nil → nil."
  [globs]
  (cond
    (nil? globs)    nil
    (string? globs) [globs]
    (vector? globs) globs
    (sequential? globs) (vec globs)
    :else [globs]))

(defn build-applies-to
  "Build :rule/applies-to from dewey + optional globs."
  [dewey-str globs]
  (let [phases (dewey-to-phases dewey-str)
        base   {:phases phases}]
    (if-let [g (normalize-globs globs)]
      (assoc base :file-globs g)
      base)))

(defn build-always-inject
  "Derive :rule/always-inject? map fragment.
   true → {:rule/always-inject? true}, false/nil → {}"
  [always-apply]
  (if (true? always-apply)
    {:rule/always-inject? true}
    {}))

(defn build-enforcement
  "Build :rule/enforcement from title."
  [title]
  {:action  :audit
   :message (str "Standard: " title)})

(defn compile-rule
  "Compile a single MDC file representation into a pack rule map.
   This is the reference implementation of the spec's field mapping."
  [{:keys [filepath frontmatter body]}]
  (let [dewey       (get frontmatter "dewey" "000")
        title       (derive-title frontmatter filepath)
        description (derive-description dewey title)
        always-apply (get frontmatter "alwaysApply")
        globs       (get frontmatter "globs")
        trimmed-body (when body (str/trim body))
        knowledge   (when (and trimmed-body (not (str/blank? trimmed-body)))
                      trimmed-body)]
    (merge
     {:rule/id                (rule-id-from-filepath filepath)
      :rule/title             title
      :rule/description       description
      :rule/severity          :info
      :rule/category          dewey
      :rule/applies-to        (build-applies-to dewey globs)
      :rule/detection         {:type :custom}
      :rule/enforcement       (build-enforcement title)}
     (build-always-inject always-apply)
     (when knowledge
       {:rule/knowledge-content knowledge}))))

;; ---------------------------------------------------------------------------
;; Layer 0 — Agent behavior extraction helpers
;; ---------------------------------------------------------------------------

(defn extract-agent-behavior-section
  "Extract ## Agent behavior section content from body.
   Returns nil if section not found."
  [body]
  (when body
    (let [lines (str/split-lines body)
          start-idx (some (fn [[i line]]
                           (when (re-matches #"(?i)^##\s+Agent\s+behavior.*" line)
                             i))
                         (map-indexed vector lines))]
      (when start-idx
        (let [rest-lines (drop (inc start-idx) lines)
              content-lines (take-while
                             (fn [line]
                               (not (re-matches #"^##\s+.*" line)))
                             rest-lines)
              content (str/trim (str/join "\n" content-lines))]
          (when (not (str/blank? content))
            content))))))

(defn extract-first-paragraph
  "Extract first non-heading paragraph from body."
  [body]
  (when body
    (let [lines (str/split-lines body)
          ;; Skip leading headings and blank lines
          content-lines (drop-while
                         (fn [line]
                           (or (str/blank? line)
                               (str/starts-with? line "#")))
                         lines)
          ;; Take until blank line
          para-lines (take-while (complement str/blank?) content-lines)
          para (str/trim (str/join " " para-lines))]
      (when (not (str/blank? para))
        para))))

;; ---------------------------------------------------------------------------
;; Layer 1 — Design spec data (from the .edn)
;; ---------------------------------------------------------------------------

(def complete-inventory
  "All .mdc files and their expected rule IDs from Section 3."
  {"foundations/stratified-design.mdc"       :std/stratified-design
   "foundations/simple-made-easy.mdc"        :std/simple-made-easy
   "foundations/code-quality.mdc"            :std/code-quality
   "foundations/result-handling.mdc"         :std/result-handling
   "foundations/validation-boundaries.mdc"   :std/validation-boundaries
   "foundations/specification-standards.mdc" :std/specification-standards
   "foundations/localization.mdc"            :std/localization
   "languages/clojure.mdc"                  :std/clojure
   "languages/python.mdc"                   :std/python
   "frameworks/polylith.mdc"                :std/polylith
   "frameworks/kubernetes.mdc"              :std/kubernetes
   "testing/standards.mdc"                  :std/standards
   "workflows/git-branch-management.mdc"    :std/git-branch-management
   "workflows/pre-commit-discipline.mdc"    :std/pre-commit-discipline
   "workflows/git-worktrees.mdc"            :std/git-worktrees
   "workflows/pr-documentation.mdc"         :std/pr-documentation
   "workflows/pr-layering.mdc"              :std/pr-layering
   "workflows/datever.mdc"                  :std/datever
   "project/header-copyright.mdc"           :std/header-copyright
   "meta/rule-format.mdc"                   :std/rule-format
   "index.mdc"                              :std/index})

(def all-phases #{:plan :implement :review :verify :release})

;; ===========================================================================
;; Section 1: Rule ID Derivation Tests
;; ===========================================================================

(deftest rule-id-derivation-test
  (testing "Rule ID is derived from filename slug with :std/ namespace"
    (are [filepath expected-id]
         (= expected-id (rule-id-from-filepath filepath))
      "foundations/stratified-design.mdc"       :std/stratified-design
      "languages/clojure.mdc"                   :std/clojure
      "workflows/pre-commit-discipline.mdc"     :std/pre-commit-discipline
      "testing/standards.mdc"                   :std/standards
      "index.mdc"                               :std/index
      "meta/rule-format.mdc"                    :std/rule-format
      "project/header-copyright.mdc"            :std/header-copyright))

  (testing "Directory path is NOT included in the id"
    (is (= :std/clojure (rule-id-from-filepath "languages/clojure.mdc")))
    (is (= :std/clojure (rule-id-from-filepath "deeply/nested/path/clojure.mdc"))))

  (testing "Complete inventory matches expected IDs"
    (doseq [[filepath expected-id] complete-inventory]
      (is (= expected-id (rule-id-from-filepath filepath))
          (str "ID mismatch for " filepath)))))

(deftest slug-from-filename-test
  (testing "Strips .mdc extension from filename only"
    (are [filepath expected]
         (= expected (slug-from-filename filepath))
      "foundations/stratified-design.mdc" "stratified-design"
      "index.mdc"                        "index"
      "languages/clojure.mdc"            "clojure"
      "workflows/pr-layering.mdc"        "pr-layering"))

  (testing "Handles nested directory paths"
    (is (= "foo" (slug-from-filename "a/b/c/foo.mdc")))))

;; ===========================================================================
;; Section 2: Title Derivation Tests
;; ===========================================================================

(deftest title-derivation-test
  (testing "Uses description frontmatter verbatim when present"
    (let [fm {"description" "Stratified Design — enforce one-way dependencies"}]
      (is (= "Stratified Design — enforce one-way dependencies"
             (derive-title fm "foundations/stratified-design.mdc")))))

  (testing "Falls back to title-cased slug when description missing"
    (is (= "Code Quality" (derive-title {} "foundations/code-quality.mdc")))
    (is (= "Pre Commit Discipline" (derive-title {} "workflows/pre-commit-discipline.mdc"))))

  (testing "Falls back to title-cased slug when description is blank"
    (is (= "Code Quality" (derive-title {"description" ""} "foundations/code-quality.mdc")))
    (is (= "Code Quality" (derive-title {"description" "  "} "foundations/code-quality.mdc")))))

(deftest title-from-slug-test
  (testing "Converts hyphens to spaces and title-cases"
    (are [slug expected]
         (= expected (title-from-slug slug))
      "code-quality"            "Code Quality"
      "pre-commit-discipline"   "Pre Commit Discipline"
      "index"                   "Index"
      "stratified-design"       "Stratified Design"
      "git-branch-management"   "Git Branch Management")))

;; ===========================================================================
;; Section 3: Description Generation Tests
;; ===========================================================================

(deftest description-generation-test
  (testing "Format: 'Engineering standard (<category>): <title>'"
    (is (= "Engineering standard (001): Stratified Design — enforce one-way dependencies"
           (derive-description "001" "Stratified Design — enforce one-way dependencies")))
    (is (= "Engineering standard (210): Clojure Polylith + per-file stratified design"
           (derive-description "210" "Clojure Polylith + per-file stratified design")))
    (is (= "Engineering standard (000): Index"
           (derive-description "000" "Index")))))

;; ===========================================================================
;; Section 4: Dewey-to-Phases Mapping Tests
;; ===========================================================================

(deftest dewey-to-phases-test
  (testing "Foundations (0-99) → all phases"
    (is (= all-phases (dewey-to-phases "001")))
    (is (= all-phases (dewey-to-phases "000")))
    (is (= all-phases (dewey-to-phases "099"))))

  (testing "Tools (100-199) → implement + review"
    (is (= #{:implement :review} (dewey-to-phases "100")))
    (is (= #{:implement :review} (dewey-to-phases "150"))))

  (testing "Languages (200-299) → implement + review"
    (is (= #{:implement :review} (dewey-to-phases "210")))
    (is (= #{:implement :review} (dewey-to-phases "200"))))

  (testing "Frameworks (300-399) → plan + implement + review"
    (is (= #{:plan :implement :review} (dewey-to-phases "300")))
    (is (= #{:plan :implement :review} (dewey-to-phases "350"))))

  (testing "Testing (400-499) → implement + verify"
    (is (= #{:implement :verify} (dewey-to-phases "400")))
    (is (= #{:implement :verify} (dewey-to-phases "450"))))

  (testing "Operations (500-599) → implement + review"
    (is (= #{:implement :review} (dewey-to-phases "500")))
    (is (= #{:implement :review} (dewey-to-phases "550"))))

  (testing "Documentation (600-699) → implement + review"
    (is (= #{:implement :review} (dewey-to-phases "600"))))

  (testing "Workflows (700-799) → all phases"
    (is (= all-phases (dewey-to-phases "700")))
    (is (= all-phases (dewey-to-phases "715"))))

  (testing "Project-specific (800-899) → implement + review"
    (is (= #{:implement :review} (dewey-to-phases "800"))))

  (testing "Meta (900-999) → empty set (never injected)"
    (is (= #{} (dewey-to-phases "900")))
    (is (= #{} (dewey-to-phases "999"))))

  (testing "Boundary values at range transitions"
    ;; End of foundations → start of tools
    (is (= all-phases (dewey-to-phases "99")))
    (is (= #{:implement :review} (dewey-to-phases "100")))
    ;; End of tools → start of languages
    (is (= #{:implement :review} (dewey-to-phases "199")))
    (is (= #{:implement :review} (dewey-to-phases "200"))))

  (testing "Dewey range coverage is contiguous from 0-999"
    (doseq [code (range 0 1000)]
      (is (set? (dewey-to-phases (str code)))
          (str "No phase set for dewey code " code)))))

(deftest dewey-default-phases-test
  (testing "Unparseable dewey defaults to implement + review"
    (is (= default-phases (dewey-to-phases "")))))

(deftest dewey-missing-defaults-to-000-test
  (testing "Missing dewey frontmatter defaults to '000' (foundation phases)"
    ;; Per edge case: "Missing dewey frontmatter" → default to '000'
    (is (= all-phases (dewey-to-phases "000")))))

;; ===========================================================================
;; Section 5: alwaysApply → :rule/always-inject? Mapping Tests
;; ===========================================================================

(deftest always-inject-mapping-test
  (testing "alwaysApply: true → {:rule/always-inject? true}"
    (is (= {:rule/always-inject? true} (build-always-inject true))))

  (testing "alwaysApply: false → {} (key omitted)"
    (is (= {} (build-always-inject false))))

  (testing "alwaysApply: nil (absent) → {} (key omitted)"
    (is (= {} (build-always-inject nil))))

  (testing "Consumers get nil (falsy) for missing always-inject?"
    (let [rule-without (merge {} (build-always-inject false))]
      (is (nil? (:rule/always-inject? rule-without))))))

;; ===========================================================================
;; Section 6: Applies-To (Phases + Globs) Tests
;; ===========================================================================

(deftest applies-to-test
  (testing "Dewey-only → phases from dewey, no globs"
    (is (= {:phases #{:plan :implement :review :verify :release}}
           (build-applies-to "001" nil))))

  (testing "Dewey + globs → phases + file-globs"
    (is (= {:phases     #{:implement :review}
            :file-globs ["components/**/src/**/*.clj"]}
           (build-applies-to "210" ["components/**/src/**/*.clj"]))))

  (testing "Meta dewey → empty phases"
    (is (= {:phases #{}} (build-applies-to "900" nil))))

  (testing "Meta dewey + globs → empty phases + globs"
    (is (= {:phases     #{}
            :file-globs [".cursor/rules/**/*.mdc"]}
           (build-applies-to "900" [".cursor/rules/**/*.mdc"])))))

(deftest globs-normalization-test
  (testing "String globs wrapped in vector"
    (is (= ["*.clj"] (normalize-globs "*.clj"))))

  (testing "Vector globs passed through"
    (is (= ["*.clj" "*.cljc"] (normalize-globs ["*.clj" "*.cljc"]))))

  (testing "nil globs → nil"
    (is (nil? (normalize-globs nil)))))

;; ===========================================================================
;; Section 7: Constant/Default Fields Tests
;; ===========================================================================

(deftest constant-fields-test
  (testing "Severity is always :info for knowledge rules"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body "content"})]
      (is (= :info (:rule/severity rule)))))

  (testing "Detection is always {:type :custom}"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body "content"})]
      (is (= {:type :custom} (:rule/detection rule)))))

  (testing "Enforcement is always {:action :audit :message 'Standard: <title>'}"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"
                                            "description" "My Test Rule"}
                              :body "content"})]
      (is (= {:action :audit :message "Standard: My Test Rule"}
             (:rule/enforcement rule))))))

;; ===========================================================================
;; Section 8: Knowledge Content Tests
;; ===========================================================================

(deftest knowledge-content-test
  (testing "Body text preserved as :rule/knowledge-content"
    (let [body "# Heading\n\nSome content here."
          rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body body})]
      (is (= body (:rule/knowledge-content rule)))))

  (testing "Leading/trailing whitespace trimmed"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body "  \n  content  \n  "})]
      (is (= "content" (:rule/knowledge-content rule)))))

  (testing "Empty body → :rule/knowledge-content omitted"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body ""})]
      (is (not (contains? rule :rule/knowledge-content)))))

  (testing "Whitespace-only body → :rule/knowledge-content omitted"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body "   \n\n   "})]
      (is (not (contains? rule :rule/knowledge-content)))))

  (testing "Nil body → :rule/knowledge-content omitted"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body nil})]
      (is (not (contains? rule :rule/knowledge-content))))))

;; ===========================================================================
;; Section 9: Agent Behavior Extraction Tests
;; ===========================================================================

(deftest agent-behavior-section-extraction-test
  (testing "Extracts content from ## Agent behavior section"
    (let [body "# Heading\n\nIntro paragraph.\n\n## Agent behavior\n\n- Do X.\n- Do Y.\n\n## Next section"]
      (is (= "- Do X.\n- Do Y." (extract-agent-behavior-section body)))))

  (testing "Case-insensitive heading match"
    (let [body "## agent behavior\n\nDo stuff."]
      (is (= "Do stuff." (extract-agent-behavior-section body)))))

  (testing "Extracts to end of file when no next heading"
    (let [body "## Agent behavior\n\n- Be concise.\n- Be clear."]
      (is (= "- Be concise.\n- Be clear." (extract-agent-behavior-section body)))))

  (testing "Returns nil when section not present"
    (let [body "# Heading\n\nJust content, no agent behavior section."]
      (is (nil? (extract-agent-behavior-section body)))))

  (testing "Returns nil for empty section"
    (let [body "## Agent behavior\n\n## Next heading"]
      (is (nil? (extract-agent-behavior-section body)))))

  (testing "Returns nil for nil body"
    (is (nil? (extract-agent-behavior-section nil)))))

(deftest first-paragraph-extraction-test
  (testing "Extracts first non-heading paragraph"
    (let [body "# Code Quality (ALWAYS)\n\nEvery function must read as a pipeline: data enters, transforms, exits.\n\nMore text."]
      (is (= "Every function must read as a pipeline: data enters, transforms, exits."
             (extract-first-paragraph body)))))

  (testing "Skips leading headings and blank lines"
    (let [body "\n# Heading\n## Sub\n\nActual content here."]
      (is (= "Actual content here." (extract-first-paragraph body)))))

  (testing "Returns nil for heading-only body"
    (let [body "# Just a heading\n## And subheading"]
      (is (nil? (extract-first-paragraph body)))))

  (testing "Returns nil for nil body"
    (is (nil? (extract-first-paragraph nil)))))

;; ===========================================================================
;; Section 10: Worked Example A — Foundation alwaysApply Rule
;; ===========================================================================

(deftest worked-example-a-stratified-design-test
  (let [rule (compile-rule
              {:filepath    "foundations/stratified-design.mdc"
               :frontmatter {"dewey"       "001"
                             "description" "Stratified Design — enforce one-way dependencies and clear data flow"
                             "alwaysApply" true}
               :body        "# Stratified Design (ALWAYS)\n\nUse a single-direction DAG...\n\n## Agent behavior\n\n- Before writing code..."})]

    (testing ":rule/id derived from filename slug"
      (is (= :std/stratified-design (:rule/id rule))))

    (testing ":rule/title from description frontmatter"
      (is (= "Stratified Design — enforce one-way dependencies and clear data flow"
             (:rule/title rule))))

    (testing ":rule/description generated from dewey + title"
      (is (= "Engineering standard (001): Stratified Design — enforce one-way dependencies and clear data flow"
             (:rule/description rule))))

    (testing ":rule/severity is :info"
      (is (= :info (:rule/severity rule))))

    (testing ":rule/category preserves dewey string"
      (is (= "001" (:rule/category rule))))

    (testing ":rule/always-inject? is true (alwaysApply: true)"
      (is (true? (:rule/always-inject? rule))))

    (testing ":rule/applies-to has all phases for dewey 001"
      (is (= {:phases all-phases}
             (:rule/applies-to rule))))

    (testing ":rule/detection is {:type :custom}"
      (is (= {:type :custom} (:rule/detection rule))))

    (testing ":rule/enforcement is audit with standard message"
      (is (= {:action  :audit
              :message "Standard: Stratified Design — enforce one-way dependencies and clear data flow"}
             (:rule/enforcement rule))))

    (testing ":rule/knowledge-content contains full body"
      (is (some? (:rule/knowledge-content rule)))
      (is (str/starts-with? (:rule/knowledge-content rule) "# Stratified Design")))))

;; ===========================================================================
;; Section 11: Worked Example B — Language Rule with Globs
;; ===========================================================================

(deftest worked-example-b-clojure-test
  (let [globs ["components/**/src/**/*.clj"
               "components/**/src/**/*.cljc"
               "bases/**/src/**/*.clj"
               "bases/**/src/**/*.cljc"
               "projects/**/src/**/*.clj"
               "projects/**/src/**/*.cljc"]
        rule (compile-rule
              {:filepath    "languages/clojure.mdc"
               :frontmatter {"dewey"       "210"
                             "description" "Clojure Polylith + per-file stratified design"
                             "globs"       globs}
               :body        "# Clojure style guidelines\n\n## Polylith architecture (ALWAYS)..."})]

    (testing ":rule/id is :std/clojure"
      (is (= :std/clojure (:rule/id rule))))

    (testing ":rule/title from description"
      (is (= "Clojure Polylith + per-file stratified design" (:rule/title rule))))

    (testing ":rule/category is '210'"
      (is (= "210" (:rule/category rule))))

    (testing ":rule/always-inject? omitted (no alwaysApply)"
      (is (not (contains? rule :rule/always-inject?))))

    (testing ":rule/applies-to has implement+review phases and file-globs"
      (is (= #{:implement :review}
             (get-in rule [:rule/applies-to :phases])))
      (is (= globs
             (get-in rule [:rule/applies-to :file-globs]))))

    (testing ":rule/description generated correctly"
      (is (= "Engineering standard (210): Clojure Polylith + per-file stratified design"
             (:rule/description rule))))))

;; ===========================================================================
;; Section 12: Worked Example C — Meta Rule (Not Injected)
;; ===========================================================================

(deftest worked-example-c-meta-rule-test
  (let [rule (compile-rule
              {:filepath    "meta/rule-format.mdc"
               :frontmatter {"dewey"       "900"
                             "description" "Use ALWAYS when asked to CREATE A RULE or UPDATE A RULE..."
                             "globs"       [".cursor/rules/**/*.mdc"]}
               :body        "# LLM Rules Format\n\n## Core Structure..."})]

    (testing ":rule/id is :std/rule-format"
      (is (= :std/rule-format (:rule/id rule))))

    (testing ":rule/category is '900'"
      (is (= "900" (:rule/category rule))))

    (testing ":rule/always-inject? omitted"
      (is (not (contains? rule :rule/always-inject?))))

    (testing ":rule/applies-to has empty phases (meta) but preserves globs"
      (is (= #{} (get-in rule [:rule/applies-to :phases])))
      (is (= [".cursor/rules/**/*.mdc"]
             (get-in rule [:rule/applies-to :file-globs]))))))

;; ===========================================================================
;; Section 13: Worked Example D — Testing Rule (alwaysApply)
;; ===========================================================================

(deftest worked-example-d-testing-rule-test
  (let [rule (compile-rule
              {:filepath    "testing/standards.mdc"
               :frontmatter {"dewey"       "400"
                             "description" "Testing standards — factory functions, same code quality as production, no duplication"
                             "alwaysApply" true}
               :body        "# Testing Standards (ALWAYS)\n\n**Test code is production code.**..."})]

    (testing ":rule/id is :std/standards"
      (is (= :std/standards (:rule/id rule))))

    (testing ":rule/always-inject? true"
      (is (true? (:rule/always-inject? rule))))

    (testing ":rule/applies-to has implement+verify phases (dewey 400)"
      (is (= #{:implement :verify}
             (get-in rule [:rule/applies-to :phases]))))

    (testing ":rule/enforcement audit with title"
      (is (= {:action :audit
              :message "Standard: Testing standards — factory functions, same code quality as production, no duplication"}
             (:rule/enforcement rule))))))

;; ===========================================================================
;; Section 14: Edge Case Tests
;; ===========================================================================

(deftest edge-case-missing-dewey-test
  (testing "Missing dewey → default to '000', phases all"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {}
                              :body "content"})]
      (is (= "000" (:rule/category rule)))
      (is (= all-phases
             (get-in rule [:rule/applies-to :phases]))))))

(deftest edge-case-missing-description-test
  (testing "Missing description → title derived from slug"
    (let [rule (compile-rule {:filepath "foundations/code-quality.mdc"
                              :frontmatter {"dewey" "001"}
                              :body "content"})]
      (is (= "Code Quality" (:rule/title rule))))))

(deftest edge-case-empty-body-test
  (testing "Empty body → knowledge-content omitted, rule still valid"
    (let [rule (compile-rule {:filepath "stub.mdc"
                              :frontmatter {"dewey" "001"
                                            "description" "Stub rule"}
                              :body ""})]
      (is (not (contains? rule :rule/knowledge-content)))
      (is (= :std/stub (:rule/id rule)))
      (is (= "Stub rule" (:rule/title rule))))))

(deftest edge-case-duplicate-slugs-test
  (testing "Duplicate filename slugs must be detectable"
    (let [files ["foundations/foo.mdc" "workflows/foo.mdc"]
          slugs (map slug-from-filename files)]
      (is (not= (count slugs) (count (set slugs)))
          "Duplicate slugs should be detected by ETL"))))

(deftest edge-case-always-apply-meta-test
  (testing "alwaysApply: true + dewey 900 → always-inject true but empty phases"
    (let [rule (compile-rule {:filepath "meta/weird.mdc"
                              :frontmatter {"dewey"       "900"
                                            "alwaysApply" true
                                            "description" "Weird meta rule"}
                              :body "some content"})]
      (is (true? (:rule/always-inject? rule)))
      (is (= #{} (get-in rule [:rule/applies-to :phases]))))))

(deftest edge-case-globs-string-instead-of-list-test
  (testing "String globs normalized to vector"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "210"
                                            "globs" "*.clj"}
                              :body "content"})]
      (is (= ["*.clj"] (get-in rule [:rule/applies-to :file-globs]))))))

(deftest edge-case-body-only-headings-test
  (testing "Body with only headings → knowledge-content present, agent-behavior nil"
    (let [body "# Just a heading\n## And subheading"]
      (is (nil? (extract-agent-behavior-section body)))
      (is (nil? (extract-first-paragraph body))))))

(deftest edge-case-index-mdc-test
  (testing "index.mdc compiled like any other file, ID :std/index"
    (let [rule (compile-rule {:filepath "index.mdc"
                              :frontmatter {"dewey" "000"
                                            "description" "Master index of all rules"
                                            "alwaysApply" false}
                              :body "# Rules Catalog"})]
      (is (= :std/index (:rule/id rule)))
      (is (= all-phases (get-in rule [:rule/applies-to :phases])))
      ;; alwaysApply false → always-inject? omitted
      (is (not (contains? rule :rule/always-inject?))))))

;; ===========================================================================
;; Section 15: Inventory Completeness Tests
;; ===========================================================================

(deftest inventory-covers-all-mdc-files-test
  (testing "Complete inventory has 21 entries (all .standards/*.mdc files + index.mdc)"
    (is (= 21 (count complete-inventory))))

  (testing "All rule IDs in inventory are unique"
    (let [ids (vals complete-inventory)]
      (is (= (count ids) (count (set ids))))))

  (testing "All rule IDs use :std/ namespace"
    (doseq [[_ rule-id] complete-inventory]
      (is (= "std" (namespace rule-id))
          (str rule-id " should use :std/ namespace")))))

(deftest inventory-matches-filesystem-test
  (testing "Inventory paths match actual .standards/ directory structure"
    (let [inventory-paths (set (keys complete-inventory))
          ;; Known actual files from .standards/ directory
          actual-files #{"workflows/git-worktrees.mdc"
                         "workflows/pre-commit-discipline.mdc"
                         "workflows/git-branch-management.mdc"
                         "workflows/datever.mdc"
                         "workflows/pr-documentation.mdc"
                         "workflows/pr-layering.mdc"
                         "meta/rule-format.mdc"
                         "foundations/validation-boundaries.mdc"
                         "foundations/stratified-design.mdc"
                         "foundations/simple-made-easy.mdc"
                         "foundations/result-handling.mdc"
                         "foundations/code-quality.mdc"
                         "foundations/specification-standards.mdc"
                         "foundations/localization.mdc"
                         "languages/python.mdc"
                         "languages/clojure.mdc"
                         "project/header-copyright.mdc"
                         "testing/standards.mdc"
                         "frameworks/kubernetes.mdc"
                         "frameworks/polylith.mdc"}
          ;; index.mdc is at root, not in .standards/ subdirectory
          inventory-without-index (disj inventory-paths "index.mdc")]
      (is (= actual-files inventory-without-index)
          (str "Missing from inventory: " (set/difference actual-files inventory-without-index)
               ", Extra in inventory: " (set/difference inventory-without-index actual-files))))))

;; ===========================================================================
;; Section 16: Output Pack Structure Tests
;; ===========================================================================

(deftest pack-structure-categories-cover-all-rules-test
  (testing "Pack categories reference all rule IDs from inventory"
    (let [category-rules #{:std/stratified-design :std/simple-made-easy
                           :std/code-quality :std/result-handling
                           :std/validation-boundaries :std/specification-standards
                           :std/localization
                           :std/clojure :std/python
                           :std/polylith :std/kubernetes
                           :std/standards
                           :std/git-branch-management :std/pre-commit-discipline
                           :std/git-worktrees :std/pr-documentation
                           :std/pr-layering :std/datever
                           :std/header-copyright
                           :std/rule-format :std/index}
          inventory-ids (set (vals complete-inventory))]
      (is (= inventory-ids category-rules)
          "Pack categories should reference exactly the inventory rule IDs"))))

(deftest pack-metadata-test
  (testing "Output pack has required identity fields"
    (let [template {:pack/id          "miniforge/standards"
                    :pack/name        "Miniforge Engineering Standards"
                    :pack/author      "miniforge.ai"
                    :pack/license     "Apache-2.0"
                    :pack/trust-level :trusted
                    :pack/authority   :authority/instruction}]
      (is (string? (:pack/id template)))
      (is (string? (:pack/name template)))
      (is (string? (:pack/author template)))
      (is (= :trusted (:pack/trust-level template)))
      (is (= :authority/instruction (:pack/authority template))))))

;; ===========================================================================
;; Section 17: Field Mapping Completeness Tests
;; ===========================================================================

(deftest all-frontmatter-fields-mapped-test
  (testing "All known MDC frontmatter fields have defined mappings"
    (let [known-frontmatter-fields #{"dewey" "description" "alwaysApply" "globs"}
          mapped-sources #{:filename-slug
                           [:frontmatter "description"]
                           [:frontmatter "dewey"]
                           [:frontmatter "alwaysApply"]
                           [:frontmatter "dewey" "globs"]
                           [:derived]
                           :mdc-body
                           [:mdc-body "## Agent behavior"]
                           :constant}
          ;; Verify each frontmatter field appears in at least one mapping source
          frontmatter-in-sources (set (for [src mapped-sources
                                            :when (vector? src)
                                            field (rest src)
                                            :when (string? field)]
                                        field))]
      ;; description, dewey, alwaysApply, globs should all be covered
      (is (set/subset? #{"description" "dewey" "alwaysApply" "globs"}
                       (set/union frontmatter-in-sources #{"globs"})) ;; globs is in compound source
          "All frontmatter fields must have mappings"))))

(deftest all-rule-schema-fields-produced-test
  (testing "Compiled rule produces all required schema fields"
    (let [rule (compile-rule {:filepath "test/example.mdc"
                              :frontmatter {"dewey"       "210"
                                            "description" "Example rule"
                                            "alwaysApply" true
                                            "globs"       ["*.clj"]}
                              :body        "# Example\n\nContent here."})
          required-keys #{:rule/id :rule/title :rule/description
                          :rule/severity :rule/category :rule/applies-to
                          :rule/detection :rule/enforcement}]
      (is (set/subset? required-keys (set (keys rule)))
          (str "Missing required keys: " (set/difference required-keys (set (keys rule))))))))

(deftest optional-fields-conditionally-present-test
  (testing ":rule/always-inject? present only when alwaysApply is true"
    (let [rule-with    (compile-rule {:filepath "a.mdc" :frontmatter {"alwaysApply" true} :body "x"})
          rule-without (compile-rule {:filepath "b.mdc" :frontmatter {} :body "x"})]
      (is (contains? rule-with :rule/always-inject?))
      (is (not (contains? rule-without :rule/always-inject?)))))

  (testing ":rule/knowledge-content present only when body is non-empty"
    (let [rule-with    (compile-rule {:filepath "a.mdc" :frontmatter {} :body "content"})
          rule-without (compile-rule {:filepath "b.mdc" :frontmatter {} :body ""})]
      (is (contains? rule-with :rule/knowledge-content))
      (is (not (contains? rule-without :rule/knowledge-content))))))

;; ===========================================================================
;; Section 18: Schema Compatibility Tests
;; ===========================================================================

(deftest compiled-rule-matches-schema-shape-test
  (testing "Compiled rule has correct value types for schema fields"
    (let [rule (compile-rule {:filepath "foundations/stratified-design.mdc"
                              :frontmatter {"dewey" "001"
                                            "description" "Test"
                                            "alwaysApply" true}
                              :body "Body content"})]
      ;; Identity
      (is (keyword? (:rule/id rule)))
      (is (string? (:rule/title rule)))
      (is (string? (:rule/description rule)))
      (is (#{:critical :major :minor :info} (:rule/severity rule)))
      (is (string? (:rule/category rule)))

      ;; Applicability
      (is (map? (:rule/applies-to rule)))
      (is (set? (get-in rule [:rule/applies-to :phases])))

      ;; Detection
      (is (map? (:rule/detection rule)))
      (is (#{:plan-output :diff-analysis :state-comparison :content-scan :ast-analysis :custom}
           (get-in rule [:rule/detection :type])))

      ;; Enforcement
      (is (map? (:rule/enforcement rule)))
      (is (#{:hard-halt :require-approval :warn :audit}
           (get-in rule [:rule/enforcement :action])))
      (is (string? (get-in rule [:rule/enforcement :message])))

      ;; Optional
      (is (boolean? (:rule/always-inject? rule)))
      (is (string? (:rule/knowledge-content rule))))))

;; ===========================================================================
;; Section 19: Dewey Range Completeness and Non-Overlap Tests
;; ===========================================================================

(deftest dewey-ranges-non-overlapping-test
  (testing "Dewey ranges do not overlap"
    (let [ranges (mapv (fn [[lo hi _]] [lo hi]) dewey-ranges)
          sorted (sort-by first ranges)]
      (doseq [[[_ hi1] [lo2 _]] (partition 2 1 sorted)]
        (is (< hi1 lo2)
            (str "Ranges overlap: ..." hi1 "] and [" lo2 "..."))))))

(deftest dewey-ranges-cover-0-to-999-test
  (testing "Dewey ranges cover all codes from 0 to 999"
    (let [covered (set (for [[lo hi _] dewey-ranges
                             code (range lo (inc hi))]
                         code))
          full-range (set (range 0 1000))]
      (is (= full-range covered)
          (str "Uncovered codes: " (set/difference full-range covered))))))

;; ===========================================================================
;; Section 20: Phase Injection Equivalence Tests
;; ===========================================================================

(deftest phase-injection-role-equivalence-test
  (testing "Foundation rules reach all agent roles"
    (is (= all-phases (dewey-to-phases "001"))))

  (testing "Workflow rules reach all agent roles"
    (is (= all-phases (dewey-to-phases "715"))))

  (testing "Language rules reach implementer and reviewer only"
    (is (= #{:implement :review} (dewey-to-phases "210"))))

  (testing "Framework rules include planner (architecture awareness)"
    (is (contains? (dewey-to-phases "300") :plan)))

  (testing "Testing rules reach implementer and verifier"
    (is (= #{:implement :verify} (dewey-to-phases "400"))))

  (testing "Meta rules reach no agents"
    (is (empty? (dewey-to-phases "900")))))

(deftest always-inject-does-not-override-phases-test
  (testing "alwaysApply controls injection, phases control which roles"
    (let [rule (compile-rule {:filepath "testing/standards.mdc"
                              :frontmatter {"dewey" "400"
                                            "alwaysApply" true}
                              :body "content"})]
      ;; always-inject is true
      (is (true? (:rule/always-inject? rule)))
      ;; but phases are still limited to testing phases, not all
      (is (= #{:implement :verify}
             (get-in rule [:rule/applies-to :phases]))))))

;; ---------------------------------------------------------------------------
;; Rich Comment
;; ---------------------------------------------------------------------------
(comment
  (clojure.test/run-tests 'ai.miniforge.policy-pack.mdc-to-pack-mapping-test)
  :leave-this-here)
