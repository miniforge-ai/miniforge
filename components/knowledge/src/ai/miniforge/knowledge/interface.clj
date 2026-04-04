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

(ns ai.miniforge.knowledge.interface
  "Public API for the knowledge component (Zettelkasten).

   Provides agent-accessible knowledge management:
   - Zettel (atomic note) CRUD operations
   - Link management with explicit rationale
   - Query and search capabilities
   - Learning capture from agent execution
   - Agent knowledge injection

   Knowledge Structure:
   - Zettel: Atomic unit of knowledge (rule, concept, learning, example, hub)
   - Link: Connection between zettels with stated rationale
   - Hub: Structure note organizing related zettels
   - Dewey: Optional classification using Dewey-style codes"
  (:require
   [ai.miniforge.knowledge.schema :as schema]
   [ai.miniforge.knowledge.zettel :as zettel]
   [ai.miniforge.knowledge.store :as store]
   [ai.miniforge.knowledge.learning :as learning]
   [ai.miniforge.knowledge.loader :as loader]
   [ai.miniforge.knowledge.messages :as messages]
   [ai.miniforge.knowledge.trust]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def Zettel schema/Zettel)
(def ZettelSummary schema/ZettelSummary)
(def Link schema/Link)
(def LinkType schema/LinkType)
(def ZettelType schema/ZettelType)
(def Source schema/Source)
(def SourceType schema/SourceType)
(def KnowledgeQuery schema/KnowledgeQuery)
(def AgentManifest schema/AgentManifest)
(def LearningCapture schema/LearningCapture)

;------------------------------------------------------------------------------ Layer 1
;; Store creation

(def create-store
  "Create an in-memory knowledge store.

   Options:
   - :logger - Logger instance for structured logging

   Example:
     (create-store)
     (create-store {:logger my-logger})"
  store/create-store)

(def create-file-backed-store
  "Create a file-backed persistent knowledge store.

   On startup, scans the directory and loads all .edn files into
   in-memory indices for fast querying. Writes are atomic (temp + rename).

   Options:
   - :path   - Base directory (default: ~/.miniforge/knowledge)
   - :logger - Logger instance

   Example:
     (create-file-backed-store)
     (create-file-backed-store {:path \"/repo/.miniforge/knowledge\"})"
  store/create-file-backed-store)

;------------------------------------------------------------------------------ Layer 2
;; Zettel CRUD

(def create-zettel
  "Create a new zettel with required fields.

   Arguments:
   - uid     - Human-readable unique identifier
   - title   - Short descriptive title
   - content - Markdown content body
   - type    - Zettel type (:rule, :concept, :learning, :example, :hub, :question, :decision)

   Options:
   - :dewey  - Dewey classification code (e.g., '210')
   - :tags   - Vector of keyword tags
   - :links  - Vector of Link maps
   - :source - Source/provenance map
   - :author - Author string (default: 'user')

   Example:
     (create-zettel '210-clojure-ns' 'Namespace Conventions'
                    'Follow Polylith structure...' :rule
                    :dewey '210' :tags [:clojure :namespace])"
  zettel/create-zettel)

(defn put-zettel
  "Store a zettel in the knowledge store. Returns the stored zettel."
  [knowledge-store zettel-data]
  (store/put-zettel knowledge-store zettel-data))

(defn get-zettel
  "Retrieve a zettel by ID (UUID) or UID (string)."
  [knowledge-store id-or-uid]
  (if (uuid? id-or-uid)
    (store/get-zettel-by-id knowledge-store id-or-uid)
    (store/get-zettel-by-uid knowledge-store id-or-uid)))

(defn delete-zettel
  "Remove a zettel from the store by ID."
  [knowledge-store id]
  (store/delete-zettel knowledge-store id))

(defn list-zettels
  "List all zettels as lightweight summaries."
  [knowledge-store]
  (store/list-zettels knowledge-store))

(def update-zettel
  "Update a zettel with new values, setting modified timestamp."
  zettel/update-zettel)

(def validate-zettel
  "Validate a zettel against the schema.
   Returns {:valid? bool :errors ...}"
  zettel/validate-zettel)

(def zettel-summary
  "Extract a lightweight summary from a zettel."
  zettel/zettel-summary)

;------------------------------------------------------------------------------ Layer 3
;; Link management

(def create-link
  "Create a link to another zettel.

   Arguments:
   - target-id  - UUID of the target zettel
   - type       - Link type (:supports, :contradicts, :extends, :applies-to, :example-of, etc.)
   - rationale  - Explanation of WHY this connection exists (required!)

   Options:
   - :strength        - Relevance weight 0.0-1.0
   - :bidirectional?  - Whether to create backlink

   Example:
     (create-link target-uuid :extends
                  'Adds namespace details to the base Clojure rule')"
  zettel/create-link)

(def add-link
  "Add a link to a zettel. Returns updated zettel."
  zettel/add-link)

(def remove-link
  "Remove a link from a zettel by target ID. Returns updated zettel."
  zettel/remove-link)

(def get-links
  "Get links from a zettel.
   Direction: :outgoing, :incoming, or :both"
  zettel/get-links)

(def compute-backlinks
  "Given a collection of zettels, compute backlinks for each.
   Returns map of zettel-id -> [source-ids...]"
  zettel/compute-backlinks)

;------------------------------------------------------------------------------ Layer 4
;; Query and search

(defn query-knowledge
  "Query zettels matching criteria.

   Query options:
   - :tags            - Filter by tags (zettel must have at least one)
   - :dewey-prefixes  - Filter by Dewey prefix (e.g., ['210' '220'])
   - :include-types   - Include only these zettel types
   - :exclude-types   - Exclude these zettel types
   - :text-search     - Case-insensitive text search in title/content
   - :limit           - Maximum results to return

   Returns vector of matching zettels."
  [knowledge-store query-map]
  (store/query knowledge-store query-map))

(def find-related
  "Find zettels related through links.

   Options:
   - :max-hops  - Maximum link traversal depth (default 1)
   - :direction - :outgoing, :incoming, or :both

   Returns vector of related zettels."
  store/find-related)

(def search
  "Full-text search across zettel titles and content.
   Returns matching zettels sorted by relevance."
  store/search)

;------------------------------------------------------------------------------ Layer 5
;; Agent injection

(def get-agent-manifest
  "Get the knowledge injection manifest for an agent role.
   Returns configuration specifying which knowledge to inject."
  store/get-agent-manifest)

(def inject-knowledge
  "Retrieve relevant knowledge for an agent based on role and context.

   Arguments:
   - store      - Knowledge store
   - agent-role - Agent role keyword (:planner, :implementer, :tester, etc.)
   - context    - Optional map with :task-type, :tags, etc.

   Returns vector of zettels relevant to the agent.

   See also: inject-knowledge-with-manifest for zettels + selection manifest."
  store/inject-knowledge)

(def inject-knowledge-with-manifest
  "Retrieve relevant knowledge for an agent with a selection manifest.

   Like inject-knowledge, but returns a map containing both the matched
   zettels and a manifest describing why each was selected. The manifest
   enables observability into which rules influenced agent behavior.

   Arguments:
   - store      - Knowledge store
   - agent-role - Agent role keyword (:planner, :implementer, :tester, etc.)
   - context    - Optional map with :task-type, :tags, etc.

   Returns:
   {:zettels  [zettel...]
    :manifest [{:id uuid, :title string, :role keyword,
                :tags-matched [keyword...], :score number}]}"
  store/inject-knowledge-with-manifest)

(def format-for-prompt
  "Format a collection of zettels as a markdown knowledge block for LLM context.

   Arguments:
   - zettels - Collection of zettels to format
   - role    - Agent role keyword (for header)

   Returns markdown string, or nil if no zettels."
  store/format-for-prompt)

(defn inject-and-format
  "Inject knowledge for an agent role and format for prompt inclusion.
   Combines inject-knowledge + format-for-prompt into a single safe call.

   Arguments:
   - knowledge-store - KnowledgeStore instance (or nil — returns nil)
   - role            - Agent role keyword (:planner, :implementer, etc.)
   - tags            - Vector of keyword tags for context filtering

   Returns formatted markdown string, or nil if no store or no matches."
  [knowledge-store role tags]
  (when knowledge-store
    (try
      (let [zettels (store/inject-knowledge knowledge-store role {:tags tags})]
        (when (seq zettels)
          (store/format-for-prompt zettels role)))
      (catch Exception _e nil))))

;------------------------------------------------------------------------------ Layer 6
;; Learning capture

(def capture-learning
  "Capture a new learning from agent execution.

   Arguments:
   - store   - Knowledge store
   - learning - Map with:
     - :type      - Source type (:inner-loop, :meta-loop)
     - :title     - Short title
     - :content   - Detailed markdown content
     - :agent     - (optional) Agent role
     - :task-id   - (optional) Task UUID
     - :tags      - (optional) Keywords
     - :confidence - (optional) 0.0-1.0

   Returns the created learning zettel."
  learning/capture-learning)

(def capture-inner-loop-learning
  "Convenience function to capture learning from repair cycle."
  learning/capture-inner-loop-learning)

(defn capture-repair-learning!
  "Capture a learning when a repair cycle succeeds.
   Safe to call unconditionally — no-ops when store is nil or iterations <= 1.

   Arguments:
   - knowledge-store - KnowledgeStore instance (or nil)
   - agent-role      - Keyword (:implementer, :reviewer, etc.)
   - task-title      - Task title string
   - iterations      - Number of repair iterations"
  [knowledge-store agent-role task-title iterations]
  (when (and knowledge-store (> iterations 1))
    (let [display-title (or task-title (name agent-role))
          params {:title display-title
                  :agent (name agent-role)
                  :iterations iterations}]
      (try
        (learning/capture-inner-loop-learning
         knowledge-store
         {:agent agent-role
          :title (messages/t :learning/repair-title params)
          :content (messages/t :learning/repair-content params)
          :tags [:repair :inner-loop (keyword (name agent-role))]})
        (catch Exception _e nil)))))

(defn capture-feedback-learning!
  "Capture review feedback as a learning.
   Safe to call unconditionally — no-ops when store is nil or feedback is empty.

   Arguments:
   - knowledge-store - KnowledgeStore instance (or nil)
   - agent-role      - Keyword (:reviewer, etc.)
   - task-title      - Task title string
   - feedback        - Feedback string or data"
  [knowledge-store agent-role task-title feedback]
  (when (and knowledge-store feedback)
    (try
      (learning/capture-learning
       knowledge-store
       {:type :inner-loop
        :agent agent-role
        :title (messages/t :learning/feedback-title {:title task-title})
        :content (str (messages/t :learning/feedback-header)
                      (if (string? feedback) feedback (pr-str feedback)))
        :tags [:review :feedback :inner-loop]
        :confidence 0.6})
      (catch Exception _e nil))))

(def capture-meta-loop-learning
  "Convenience function to capture learning from observed patterns."
  learning/capture-meta-loop-learning)

(def detect-recurring-patterns
  "Detect recurring patterns among learnings by grouping on tags.

   Scans all learnings and flags tags with 3+ occurrences.

   Options:
   - :min-occurrences - Minimum occurrences to flag (default 3)
   - :exclude-tags    - Tags to ignore (default #{:inner-loop :repair})

   Returns vector of {:tag :count :learnings}."
  learning/detect-recurring-patterns)

(def synthesize-recurring-patterns!
  "Detect recurring patterns and capture them as meta-loop learnings.

   Scans learnings for recurring tags, creates a meta-loop learning for each
   new pattern found. Skips patterns that already have a corresponding learning.

   Returns count of new patterns synthesized."
  learning/synthesize-recurring-patterns!)

(def promote-learning
  "Promote a validated learning to a rule.

   Options:
   - :new-uid     - New UID for the rule
   - :dewey       - Assign Dewey classification
   - :reviewed-by - Who reviewed/approved this"
  learning/promote-learning)

(def list-learnings
  "List all learnings, optionally filtered.

   Options:
   - :min-confidence - Minimum confidence threshold
   - :agent          - Filter by agent role
   - :promotable?    - Only return high-confidence learnings"
  learning/list-learnings)

;------------------------------------------------------------------------------ Layer 7
;; Serialization

(def zettel->markdown
  "Serialize a zettel to Markdown with YAML frontmatter."
  zettel/zettel->markdown)

(def markdown->zettel
  "Parse a Markdown file with YAML frontmatter into a zettel."
  zettel/markdown->zettel)

(def split-frontmatter
  "Split markdown content into frontmatter and body.
   Returns {:frontmatter string :body string} or nil if no frontmatter."
  (requiring-resolve 'ai.miniforge.knowledge.yaml/split-frontmatter))

(def parse-yaml-frontmatter
  "Parse YAML frontmatter into EDN map.
   Handles basic YAML: key: value, arrays, lists."
  (requiring-resolve 'ai.miniforge.knowledge.yaml/parse-yaml-frontmatter))

;------------------------------------------------------------------------------ Layer 8
;; Rule and documentation loading

(def load-rules-from-directory
  "Load all .mdc rule files from a directory into the knowledge store.

   Arguments:
   - knowledge-store - KnowledgeStore instance
   - rules-dir       - Path to .cursor/rules directory (string or File)

   Returns:
   - {:loaded int :failed int :zettels [zettel...]}

   Example:
     (load-rules-from-directory store \".cursor/rules\")"
  loader/load-rules-from-directory)

(def load-project-docs
  "Load project documentation files (agents.md, claude.md, etc.) into knowledge store.

   Arguments:
   - knowledge-store - KnowledgeStore instance
   - project-root    - Path to project root directory (string or File)

   Returns:
   - {:loaded int :failed int :files [string...]}

   Example:
     (load-project-docs store \".\")"
  loader/load-project-docs)

(def initialize-knowledge-store!
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
  loader/initialize-knowledge-store!)

;------------------------------------------------------------------------------ Layer 9
;; Trust validation (N1 §2.10.2)

(defn make-pack-ref
  "Create a pack reference with trust information.

   Arguments:
   - pack-id       - Unique identifier for the pack
   - trust-level   - :trusted, :untrusted, or :tainted
   - authority     - :authority/instruction or :authority/data

   Options:
   - :dependencies - Vector of pack-ids this pack depends on

   Returns pack reference map.

   Example:
     (make-pack-ref \"my-pack\" :trusted :authority/instruction
                    :dependencies [\"base-pack\"])"
  [pack-id trust-level authority & {:keys [dependencies]}]
  (ai.miniforge.knowledge.trust/make-pack-ref
   pack-id trust-level authority :dependencies dependencies))

(def validate-transitive-trust
  "Validate all transitive trust rules for a pack graph.

   Checks:
   1. Instruction authority is not transitive
   2. Trust level inheritance is correct
   3. Cross-trust references are valid (no cycles, missing deps)
   4. Tainted content is isolated from instruction authority

   Arguments:
   - pack-graph - Map of pack-id -> pack-ref

   Returns:
   - {:valid? true :packs [...]} if all rules pass
   - {:valid? false :errors [...]} if any rule fails

   Example:
     (validate-transitive-trust {\"pack-a\" pack-ref-a \"pack-b\" pack-ref-b})"
  ai.miniforge.knowledge.trust/validate-transitive-trust)

(def compute-inherited-trust-level
  "Compute the inherited trust level from a collection of packs.

   Rule 2: When pack A includes content from pack B, the combined content
   MUST be assigned the lower trust level.

   Arguments:
   - pack-refs - Collection of pack references being combined

   Returns the lowest trust level from all packs.

   Example:
     (compute-inherited-trust-level [trusted-pack untrusted-pack])
     ;; => :untrusted"
  ai.miniforge.knowledge.trust/compute-inherited-trust-level)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a knowledge store
  (def store (create-store))

  ;; Add a rule
  (put-zettel store
              (create-zettel "210-clojure" "Clojure Conventions"
                             "Main Clojure coding rules..." :rule
                             :dewey "210" :tags [:clojure]))

  ;; Query by tags
  (query-knowledge store {:tags [:clojure]})

  ;; Inject knowledge for an implementer agent
  (inject-knowledge store :implementer {:tags [:namespace]})

  ;; Inject with manifest for observability
  (inject-knowledge-with-manifest store :implementer {:tags [:namespace]})
  ;; => {:zettels [...] :manifest [{:id ... :title ... :role :implementer ...}]}

  ;; Capture a learning from inner loop
  (capture-inner-loop-learning store
                               {:agent :implementer
                                :title "Protocol naming collision"
                                :content "Avoid JVM method names in protocols..."
                                :tags [:clojure :protocol]})

  ;; List promotable learnings
  (list-learnings store {:promotable? true})

  ;; Serialize to markdown
  (println (zettel->markdown (get-zettel store "210-clojure")))

  :leave-this-here)
