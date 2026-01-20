(ns ai.miniforge.agent.memory
  "Agent memory and context management.
   Layer 0: Pure functions for memory operations
   Layer 1: Memory protocol and implementation
   Layer 2: Context windowing for token limits

   Memory is scoped by:
   - Agent: persistent across tasks for same agent
   - Task: isolated per task execution
   - Workflow: shared across agents in same workflow")

;------------------------------------------------------------------------------ Layer 0
;; Pure functions for memory operations

(defn make-message
  "Create a memory message entry."
  [role content & {:keys [tokens metadata]}]
  (cond-> {:memory/id (random-uuid)
           :memory/role role
           :memory/content content
           :memory/timestamp (java.util.Date.)}
    tokens   (assoc :memory/tokens tokens)
    metadata (assoc :memory/metadata metadata)))

(defn estimate-tokens
  "Estimate token count for a message.
   Uses rough heuristic: ~4 chars per token for English text."
  [content]
  (if (string? content)
    (max 1 (int (/ (count content) 4)))
    100)) ;; Default for non-string content

(defn total-tokens
  "Calculate total tokens in a sequence of messages."
  [messages]
  (reduce + 0 (map (fn [m]
                     (or (:memory/tokens m)
                         (estimate-tokens (:memory/content m))))
                   messages)))

(defn trim-to-token-limit
  "Trim messages to fit within token limit, keeping most recent.
   Always preserves system message if present.
   Returns [trimmed-messages, removed-count]."
  [messages token-limit]
  (if (<= (total-tokens messages) token-limit)
    [messages 0]
    (let [;; Separate system message(s) from others
          system-msgs (filter #(= :system (:memory/role %)) messages)
          other-msgs (remove #(= :system (:memory/role %)) messages)
          system-tokens (total-tokens system-msgs)
          available-tokens (- token-limit system-tokens)]
      (if (<= available-tokens 0)
        ;; Not even enough for system messages, return just system
        [system-msgs (count other-msgs)]
        ;; Trim from the beginning (oldest first), keep recent
        (loop [msgs (reverse other-msgs)
               kept []
               used 0]
          (if (empty? msgs)
            [(concat system-msgs (reverse kept)) (- (count other-msgs) (count kept))]
            (let [m (first msgs)
                  m-tokens (or (:memory/tokens m) (estimate-tokens (:memory/content m)))]
              (if (> (+ used m-tokens) available-tokens)
                [(concat system-msgs (reverse kept)) (- (count other-msgs) (count kept))]
                (recur (rest msgs) (conj kept m) (+ used m-tokens))))))))))

;------------------------------------------------------------------------------ Layer 1
;; Memory protocol and implementation

(defprotocol Memory
  "Protocol for agent memory storage and retrieval."

  (add-message [this role content opts]
    "Add a message to memory. opts may include :tokens, :metadata.
     Returns updated memory.")

  (get-messages [this]
    "Get all messages in chronological order.")

  (get-window [this token-limit]
    "Get messages that fit within token limit, prioritizing recent.
     Returns {:messages [...] :total-tokens int :trimmed-count int}")

  (clear [this]
    "Clear all messages. Returns empty memory.")

  (get-metadata [this]
    "Get memory metadata (scope, created-at, etc.)"))

(defrecord AgentMemory [id scope messages metadata]
  Memory
  (add-message [this role content opts]
    (let [tokens (or (:tokens opts) (estimate-tokens content))
          msg (make-message role content
                            :tokens tokens
                            :metadata (:metadata opts))]
      (assoc this :messages (conj messages msg))))

  (get-messages [_this]
    messages)

  (get-window [_this token-limit]
    (let [[windowed trimmed-count] (trim-to-token-limit messages token-limit)]
      {:messages windowed
       :total-tokens (total-tokens windowed)
       :trimmed-count trimmed-count}))

  (clear [this]
    (assoc this :messages []))

  (get-metadata [_this]
    (merge metadata
           {:memory/id id
            :memory/scope scope
            :memory/message-count (count messages)
            :memory/total-tokens (total-tokens messages)})))

;------------------------------------------------------------------------------ Layer 2
;; Memory factory functions

(defn create-memory
  "Create a new memory instance.

   Options:
   - :scope     - Memory scope (:agent, :task, :workflow)
   - :scope-id  - ID of the scope (agent-id, task-id, or workflow-id)
   - :metadata  - Additional metadata map

   Example:
     (create-memory {:scope :task :scope-id task-id})"
  ([] (create-memory {}))
  ([{:keys [scope scope-id metadata] :or {scope :task}}]
   (->AgentMemory
    (random-uuid)
    scope
    []
    (merge {:created-at (java.util.Date.)
            :scope-id scope-id}
           metadata))))

(defn add-system-message
  "Add a system message to memory. Convenience function."
  [memory content]
  (add-message memory :system content {}))

(defn add-user-message
  "Add a user message to memory. Convenience function."
  [memory content]
  (add-message memory :user content {}))

(defn add-assistant-message
  "Add an assistant message to memory. Convenience function."
  [memory content & {:keys [tokens]}]
  (add-message memory :assistant content {:tokens tokens}))

;------------------------------------------------------------------------------ Layer 3
;; Memory store for managing multiple memories

(defprotocol MemoryStore
  "Protocol for managing multiple agent memories."

  (get-memory [this memory-id]
    "Retrieve a memory by ID.")

  (save-memory [this memory]
    "Save/update a memory. Returns the memory.")

  (delete-memory [this memory-id]
    "Delete a memory by ID.")

  (list-memories [this scope scope-id]
    "List all memories for a given scope."))

(defrecord InMemoryStore [memories]
  MemoryStore
  (get-memory [_this memory-id]
    (get @memories memory-id))

  (save-memory [_this memory]
    (swap! memories assoc (:id memory) memory)
    memory)

  (delete-memory [_this memory-id]
    (swap! memories dissoc memory-id)
    nil)

  (list-memories [_this scope scope-id]
    (->> (vals @memories)
         (filter (fn [m]
                   (let [meta (get-metadata m)]
                     (and (= scope (:memory/scope meta))
                          (= scope-id (:scope-id meta)))))))))

(defn create-memory-store
  "Create an in-memory store for agent memories."
  []
  (->InMemoryStore (atom {})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a memory
  (def mem (create-memory {:scope :task :scope-id (random-uuid)}))

  ;; Add messages
  (def mem2
    (-> mem
        (add-system-message "You are a helpful coding assistant.")
        (add-user-message "Implement a factorial function")
        (add-assistant-message "(defn factorial [n] ...)")))

  ;; Get all messages
  (get-messages mem2)

  ;; Get windowed messages (fit within token limit)
  (get-window mem2 100)
  ;; => {:messages [...], :total-tokens 45, :trimmed-count 0}

  ;; Token estimation
  (estimate-tokens "Hello, world!")
  ;; => 3

  ;; Memory metadata
  (get-metadata mem2)
  ;; => {:memory/id #uuid "...", :memory/scope :task, ...}

  ;; Memory store
  (def store (create-memory-store))
  (save-memory store mem2)
  (get-memory store (:id mem2))
  (list-memories store :task (-> mem2 get-metadata :scope-id))

  :leave-this-here)
