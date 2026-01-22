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
   [ai.miniforge.knowledge.loader :as loader]))

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

   Returns vector of zettels relevant to the agent."
  store/inject-knowledge)

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

(def capture-meta-loop-learning
  "Convenience function to capture learning from observed patterns."
  learning/capture-meta-loop-learning)

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
