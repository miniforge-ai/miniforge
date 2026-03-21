(ns ai.miniforge.knowledge.store
  "Knowledge store for zettels.
   Layer 0: In-memory store protocol and implementation
   Layer 1: Query operations
   Layer 2: Agent injection"
  (:require
   [ai.miniforge.knowledge.zettel :as zettel]
   [ai.miniforge.logging.interface :as log]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Store protocol and in-memory implementation

(defprotocol KnowledgeStore
  "Protocol for zettel storage and retrieval."

  (put-zettel [this zettel]
    "Store a zettel. Returns the zettel with any computed fields.")

  (get-zettel-by-id [this id]
    "Retrieve a zettel by UUID.")

  (get-zettel-by-uid [this uid]
    "Retrieve a zettel by human-readable UID.")

  (delete-zettel [this id]
    "Remove a zettel by UUID.")

  (list-zettels [this]
    "List all zettels as summaries.")

  (query [this query-map]
    "Query zettels matching criteria. Returns vector of zettels."))

(defn matches-query?
  "Check if a zettel matches query criteria."
  [zettel {:keys [tags dewey-prefixes include-types exclude-types
                  text-search]}]
  (let [zettel-tags (set (or (:zettel/tags zettel) []))
        zettel-type (:zettel/type zettel)
        zettel-dewey (:zettel/dewey zettel)
        zettel-content (str (:zettel/title zettel) " " (:zettel/content zettel))]
    (and
     ;; Tag filter: zettel must have at least one requested tag
     (or (empty? tags)
         (some zettel-tags tags))

     ;; Dewey prefix filter: zettel dewey must start with one of the prefixes
     (or (empty? dewey-prefixes)
         (and zettel-dewey
              (some #(str/starts-with? zettel-dewey %) dewey-prefixes)))

     ;; Type inclusion filter
     (or (empty? include-types)
         (contains? (set include-types) zettel-type))

     ;; Type exclusion filter
     (or (empty? exclude-types)
         (not (contains? (set exclude-types) zettel-type)))

     ;; Text search (simple case-insensitive substring)
     (or (nil? text-search)
         (str/includes? (str/lower-case zettel-content)
                        (str/lower-case text-search))))))

(defrecord InMemoryStore [zettels-by-id zettels-by-uid logger]
  KnowledgeStore
  (put-zettel [_this zettel]
    (let [id (:zettel/id zettel)
          uid (:zettel/uid zettel)]
      (swap! zettels-by-id assoc id zettel)
      (swap! zettels-by-uid assoc uid zettel)
      (log/debug logger :knowledge :knowledge/zettel-stored
                 {:data {:id id :uid uid :type (:zettel/type zettel)}})
      zettel))

  (get-zettel-by-id [_this id]
    (get @zettels-by-id id))

  (get-zettel-by-uid [_this uid]
    (get @zettels-by-uid uid))

  (delete-zettel [_this id]
    (when-let [zettel (get @zettels-by-id id)]
      (swap! zettels-by-id dissoc id)
      (swap! zettels-by-uid dissoc (:zettel/uid zettel))
      (log/debug logger :knowledge :knowledge/zettel-deleted
                 {:data {:id id :uid (:zettel/uid zettel)}})
      true))

  (list-zettels [_this]
    (mapv zettel/zettel-summary (vals @zettels-by-id)))

  (query [_this query-map]
    (let [all-zettels (vals @zettels-by-id)
          matching (filter #(matches-query? % query-map) all-zettels)
          limited (if-let [limit (:limit query-map)]
                    (take limit matching)
                    matching)]
      (vec limited))))

(defn create-store
  "Create an in-memory knowledge store.

   Options:
   - :logger - Logger instance for structured logging

   Example:
     (create-store)
     (create-store {:logger my-logger})"
  [& [{:keys [logger]}]]
  (->InMemoryStore
   (atom {})
   (atom {})
   (or logger (log/create-logger {:min-level :info :output (fn [_])}))))

;------------------------------------------------------------------------------ Layer 1
;; Query operations

(defn find-related
  "Find zettels related to a given zettel through links.

   Options:
   - :max-hops - Maximum link traversal depth (default 1)
   - :direction - :outgoing, :incoming, or :both (default :both)

   Returns vector of related zettels sorted by hop distance."
  [store zettel-or-id & {:keys [max-hops direction] :or {max-hops 1 direction :both}}]
  (let [start-zettel (if (map? zettel-or-id)
                       zettel-or-id
                       (get-zettel-by-id store zettel-or-id))]
    (when start-zettel
      (loop [hop 1
             visited #{(:zettel/id start-zettel)}
             frontier [start-zettel]
             results []]
        (if (or (> hop max-hops) (empty? frontier))
          results
          (let [new-ids (for [z frontier
                              link (zettel/get-links z direction)
                              :let [target-id (:link/target-id link)]
                              :when (and target-id (not (visited target-id)))]
                          target-id)
                new-zettels (->> new-ids
                                 distinct
                                 (map #(get-zettel-by-id store %))
                                 (remove nil?)
                                 vec)]
            (recur (inc hop)
                   (into visited (map :zettel/id new-zettels))
                   new-zettels
                   (into results new-zettels))))))))

(defn search
  "Full-text search across zettel titles and content.
   Returns matching zettels with basic relevance scoring."
  [store text]
  (let [terms (str/split (str/lower-case text) #"\s+")
        score-fn (fn [zettel]
                   (let [content (str/lower-case
                                  (str (:zettel/title zettel) " "
                                       (:zettel/content zettel)))]
                     (reduce + (map (fn [term]
                                      (count (re-seq (re-pattern term) content)))
                                    terms))))]
    (->> (query store {:text-search text})
         (map (fn [z] (assoc z :search-score (score-fn z))))
         (sort-by :search-score >)
         (mapv #(dissoc % :search-score)))))

;------------------------------------------------------------------------------ Layer 2
;; Agent injection

(def default-agent-manifests
  "Default knowledge injection configuration per agent role."
  {:planner
   {:agent-role :planner
    :dewey-prefixes ["000" "700"]           ; Foundations, Workflows
    :tags [:architecture :planning :workflow]
    :types [:rule :decision :hub]
    :max-zettels 20}

   :implementer
   {:agent-role :implementer
    :dewey-prefixes ["200" "400"]           ; Languages, Testing
    :tags [:coding :clojure :testing]
    :types [:rule :learning :example]
    :max-zettels 15}

   :tester
   {:agent-role :tester
    :dewey-prefixes ["400"]                 ; Testing
    :tags [:testing :coverage :assertions]
    :types [:rule :example :learning]
    :max-zettels 10}

   :reviewer
   {:agent-role :reviewer
    :dewey-prefixes ["000" "200" "400"]     ; Foundations, Languages, Testing
    :tags [:code-review :quality]
    :types [:rule :learning]
    :max-zettels 15}})

(defn get-agent-manifest
  "Get the knowledge injection manifest for an agent role."
  [role]
  (get default-agent-manifests role
       {:agent-role role :types [:rule] :max-zettels 10}))

(defn inject-knowledge
  "Retrieve relevant knowledge for an agent based on role and context.

   Arguments:
   - store      - Knowledge store
   - agent-role - Agent role keyword
   - context    - Optional map with :task-type, :tags, etc.

   Returns vector of zettels relevant to the agent."
  [store agent-role & [context]]
  (let [manifest (get-agent-manifest agent-role)
        base-query {:dewey-prefixes (:dewey-prefixes manifest)
                    :include-types (:types manifest)
                    :limit (:max-zettels manifest)}
        ;; Merge any context-specific tags
        context-tags (or (:tags context) [])
        manifest-tags (or (:tags manifest) [])
        combined-query (assoc base-query
                              :tags (distinct (concat manifest-tags context-tags)))]
    (query store combined-query)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a store
  (def store (create-store))

  ;; Add some zettels
  (put-zettel store
              (zettel/create-zettel "210-clojure" "Clojure Conventions"
                                    "Main Clojure coding rules..." :rule
                                    :dewey "210" :tags [:clojure]))

  (put-zettel store
              (zettel/create-zettel "L-protocol-naming" "Protocol Naming Insight"
                                    "Learned that clear conflicts with JVM..." :learning
                                    :tags [:clojure :protocol :gotcha]))

  ;; Query by tags
  (query store {:tags [:clojure]})

  ;; Query by type
  (query store {:include-types [:rule]})

  ;; Search
  (search store "protocol naming")

  ;; Inject knowledge for implementer
  (inject-knowledge store :implementer)

  :leave-this-here)
