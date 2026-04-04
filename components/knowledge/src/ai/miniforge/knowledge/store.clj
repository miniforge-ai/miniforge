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

(ns ai.miniforge.knowledge.store
  "Knowledge store for zettels.
   Layer 0: In-memory store protocol and implementation
   Layer 0.5: File-backed persistent store
   Layer 1: Query operations
   Layer 2: Agent injection"
  (:require
   [ai.miniforge.knowledge.messages :as messages]
   [ai.miniforge.knowledge.zettel :as zettel]
   [ai.miniforge.logging.interface :as log]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
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

;------------------------------------------------------------------------------ Layer 0.5
;; File-backed persistent store

(defn- expand-path
  "Expand ~ in file paths to home directory."
  [path]
  (if (str/starts-with? path "~")
    (str (System/getProperty "user.home") (subs path 1))
    path))

(defn- ensure-directory
  "Ensure a directory exists, creating it if necessary."
  [path]
  (let [dir (io/file path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    dir))

(defn- uid->filename
  "Convert a zettel UID to a safe filename."
  [uid]
  (-> uid
      (str/replace #"[^a-zA-Z0-9_-]" "_")
      (str ".edn")))


(defn- atomic-write!
  "Write content to file atomically via temp + rename."
  [file content]
  (let [parent (.getParentFile file)
        tmp (java.io.File/createTempFile "zettel-" ".edn.tmp" parent)]
    (try
      (spit tmp content)
      (.renameTo tmp file)
      (catch Exception e
        (when (.exists tmp) (.delete tmp))
        (throw e)))))

(defn- load-zettel-file
  "Load a single .edn zettel file. Returns zettel map or nil."
  [file]
  (try
    (edn/read-string (slurp file))
    (catch Exception _e nil)))

(defn- scan-directory
  "Scan a directory for .edn zettel files and load them all.
   Returns {:by-id {uuid zettel} :by-uid {uid zettel}}."
  [dir]
  (let [d (io/file dir)]
    (if (.exists d)
      (reduce
       (fn [acc file]
         (when-let [z (load-zettel-file file)]
           (-> acc
               (assoc-in [:by-id (:zettel/id z)] z)
               (assoc-in [:by-uid (:zettel/uid z)] z))))
       {:by-id {} :by-uid {}}
       (->> (.listFiles d)
            (filter #(.isFile %))
            (filter #(str/ends-with? (.getName %) ".edn"))))
      {:by-id {} :by-uid {}})))

(defrecord FileBackedStore [base-path zettels-by-id zettels-by-uid logger]
  KnowledgeStore
  (put-zettel [_this zettel]
    (let [id (:zettel/id zettel)
          uid (:zettel/uid zettel)
          file (io/file base-path (uid->filename uid))]
      (ensure-directory base-path)
      (atomic-write! file (pr-str zettel))
      (swap! zettels-by-id assoc id zettel)
      (swap! zettels-by-uid assoc uid zettel)
      (log/debug logger :knowledge :knowledge/zettel-persisted
                 {:data {:id id :uid uid :path (.getPath file)}})
      zettel))

  (get-zettel-by-id [_this id]
    (get @zettels-by-id id))

  (get-zettel-by-uid [_this uid]
    (get @zettels-by-uid uid))

  (delete-zettel [_this id]
    (when-let [zettel (get @zettels-by-id id)]
      (let [uid (:zettel/uid zettel)
            file (io/file base-path (uid->filename uid))]
        (when (.exists file) (.delete file))
        (swap! zettels-by-id dissoc id)
        (swap! zettels-by-uid dissoc uid)
        (log/debug logger :knowledge :knowledge/zettel-deleted
                   {:data {:id id :uid uid}})
        true)))

  (list-zettels [_this]
    (mapv zettel/zettel-summary (vals @zettels-by-id)))

  (query [_this query-map]
    (let [all-zettels (vals @zettels-by-id)
          matching (filter #(matches-query? % query-map) all-zettels)
          limited (if-let [limit (:limit query-map)]
                    (take limit matching)
                    matching)]
      (vec limited))))

(defn create-file-backed-store
  "Create a file-backed persistent knowledge store.

   On startup, scans the directory and loads all .edn files into
   in-memory indices for fast querying. Writes are atomic (temp + rename).

   Options:
   - :path   - Base directory (default: ~/.miniforge/knowledge)
   - :logger - Logger instance

   Example:
     (create-file-backed-store)
     (create-file-backed-store {:path \"/repo/.miniforge/knowledge\"})"
  [& [{:keys [path logger]}]]
  (let [base-path (expand-path (or path "~/.miniforge/knowledge"))
        _ (ensure-directory base-path)
        log (or logger (log/create-logger {:min-level :info :output (fn [_])}))
        initial (scan-directory base-path)]
    (log/debug log :knowledge :knowledge/store-initialized
               {:data {:path base-path
                       :zettel-count (count (:by-id initial))}})
    (->FileBackedStore
     base-path
     (atom (:by-id initial))
     (atom (:by-uid initial))
     log)))

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

(def ^:private manifest-score-weights
  "Weights for computing manifest entry relevance scores.
   Each weight controls how much a particular match type contributes
   to the total score used for ranking and observability."
  {:tag-match   1.0   ; per matching tag
   :dewey-match 1.0   ; dewey classification prefix match
   :type-match  0.5}) ; zettel type match

(defn- compute-manifest-entry
  "Compute a manifest entry for a zettel matched during knowledge injection.

   Scores each zettel based on how well it matches the query criteria
   using weights from `manifest-score-weights`.

   Returns map with :id, :title, :role, :tags-matched, and :score."
  [zettel agent-role query-tags dewey-prefixes query-types]
  (let [zettel-tags   (set (get zettel :zettel/tags []))
        zettel-dewey  (:zettel/dewey zettel)
        zettel-type   (:zettel/type zettel)
        tags-matched  (vec (filter zettel-tags query-tags))
        tag-score     (* (double (count tags-matched))
                         (:tag-match manifest-score-weights))
        dewey-score   (if (and zettel-dewey
                               (seq dewey-prefixes)
                               (some #(str/starts-with? zettel-dewey %) dewey-prefixes))
                        (:dewey-match manifest-score-weights)
                        0.0)
        type-score    (if (and (seq query-types)
                               (contains? (set query-types) zettel-type))
                        (:type-match manifest-score-weights)
                        0.0)
        total-score   (+ tag-score dewey-score type-score)]
    {:id           (:zettel/id zettel)
     :title        (:zettel/title zettel)
     :role         agent-role
     :tags-matched tags-matched
     :score        total-score}))

(defn inject-knowledge-with-manifest
  "Retrieve relevant knowledge for an agent with a selection manifest.

   Like inject-knowledge, but returns a map containing both the matched
   zettels and a manifest describing why each was selected. The manifest
   enables observability into which rules influenced agent behavior.

   Logs rule selection at two levels:
   - :debug — individual matched rules with scores and total count
   - :info  — summary (N rules from K categories for agent :role)

   Arguments:
   - store      - Knowledge store
   - agent-role - Agent role keyword (:planner, :implementer, :tester, etc.)
   - context    - Optional map with :task-type, :tags, etc.

   Returns:
   {:zettels  [zettel...]
    :manifest [{:id uuid, :title string, :role keyword,
                :tags-matched [keyword...], :score number}]}"
  [store agent-role & [context]]
  (let [agent-manifest  (get-agent-manifest agent-role)
        base-query      {:dewey-prefixes (:dewey-prefixes agent-manifest)
                         :include-types  (:types agent-manifest)
                         :limit          (:max-zettels agent-manifest)}
        context-tags    (get context :tags [])
        manifest-tags   (get agent-manifest :tags [])
        all-tags        (vec (distinct (concat manifest-tags context-tags)))
        combined-query  (assoc base-query :tags all-tags)
        zettels         (query store combined-query)
        dewey-prefixes  (get agent-manifest :dewey-prefixes [])
        query-types     (get agent-manifest :types [])
        manifest-entries (mapv #(compute-manifest-entry
                                  % agent-role all-tags
                                  dewey-prefixes query-types)
                               zettels)
        logger          (:logger store)]
    ;; Structured logging for rule selection observability
    (when logger
      (log/debug logger :knowledge :knowledge/rules-selected
                 {:data {:agent-role   agent-role
                         :total-count  (count manifest-entries)
                         :rules        (mapv (fn [entry]
                                               {:id           (:id entry)
                                                :title        (:title entry)
                                                :score        (:score entry)
                                                :tags-matched (:tags-matched entry)})
                                             manifest-entries)}})
      (let [categories (into #{} (keep :zettel/type) zettels)]
        (log/info logger :knowledge :knowledge/rules-injected
                  {:message (str (count manifest-entries) " rules from "
                                 (count categories) " categories for agent :"
                                 (name agent-role))
                   :data {:agent-role     agent-role
                          :rule-count     (count manifest-entries)
                          :categories     (vec (sort-by name categories))
                          :category-count (count categories)}})))
    {:zettels  zettels
     :manifest manifest-entries}))

(defn inject-knowledge
  "Retrieve relevant knowledge for an agent based on role and context.

   Arguments:
   - store      - Knowledge store
   - agent-role - Agent role keyword
   - context    - Optional map with :task-type, :tags, etc.

   Returns vector of zettels relevant to the agent.

   See also: inject-knowledge-with-manifest for zettels + selection manifest."
  [store agent-role & [context]]
  (:zettels (inject-knowledge-with-manifest store agent-role context)))

;------------------------------------------------------------------------------ Layer 3
;; Prompt formatting

(defn format-zettel-for-prompt
  "Format a single zettel as a compact markdown block for LLM context."
  [zettel]
  (str "### " (:zettel/title zettel)
       (when-let [dewey (:zettel/dewey zettel)]
         (str " [" dewey "]"))
       (when (= :learning (:zettel/type zettel))
         " (learning)")
       "\n"
       (:zettel/content zettel)
       "\n"))

(defn format-for-prompt
  "Format a collection of zettels as a markdown knowledge block for LLM context.

   Arguments:
   - zettels - Collection of zettels to format
   - role    - Agent role keyword (for header)

   Returns markdown string, or nil if no zettels."
  [zettels role]
  (when (seq zettels)
    (str (messages/t :prompt/header {:role (name role)})
         (messages/t :prompt/preamble)
         (apply str (map format-zettel-for-prompt zettels))
         (messages/t :prompt/separator))))

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

  ;; Inject knowledge with manifest for observability
  (inject-knowledge-with-manifest store :implementer)
  ;; => {:zettels [...] :manifest [{:id #uuid "..." :title "..." :role :implementer ...}]}

  :leave-this-here)
