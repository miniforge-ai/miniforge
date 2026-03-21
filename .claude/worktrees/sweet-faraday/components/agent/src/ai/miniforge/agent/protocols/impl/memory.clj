(ns ai.miniforge.agent.protocols.impl.memory
  "Implementation functions for Memory and MemoryStore protocols.

   These functions implement the protocol methods and are used by the
   AgentMemory and InMemoryStore records.")

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
;; Memory protocol implementations

(defn add-message-impl
  "Implementation of add-message protocol method."
  [memory role content opts]
  (let [tokens (or (:tokens opts) (estimate-tokens content))
        msg (make-message role content
                          :tokens tokens
                          :metadata (:metadata opts))
        messages (:messages memory)]
    (assoc memory :messages (conj messages msg))))

(defn get-messages-impl
  "Implementation of get-messages protocol method."
  [memory]
  (:messages memory))

(defn get-window-impl
  "Implementation of get-window protocol method."
  [memory token-limit]
  (let [messages (:messages memory)
        [windowed trimmed-count] (trim-to-token-limit messages token-limit)]
    {:messages windowed
     :total-tokens (total-tokens windowed)
     :trimmed-count trimmed-count}))

(defn clear-messages-impl
  "Implementation of clear-messages protocol method."
  [memory]
  (assoc memory :messages []))

(defn get-metadata-impl
  "Implementation of get-metadata protocol method."
  [memory]
  (let [{:keys [id scope messages metadata]} memory]
    (merge metadata
           {:memory/id id
            :memory/scope scope
            :memory/message-count (count messages)
            :memory/total-tokens (total-tokens messages)})))

;------------------------------------------------------------------------------ Layer 2
;; MemoryStore protocol implementations

(defn get-memory-impl
  "Implementation of get-memory protocol method."
  [store memory-id]
  (get @(:memories store) memory-id))

(defn save-memory-impl
  "Implementation of save-memory protocol method."
  [store memory]
  (swap! (:memories store) assoc (:id memory) memory)
  memory)

(defn delete-memory-impl
  "Implementation of delete-memory protocol method."
  [store memory-id]
  (swap! (:memories store) dissoc memory-id)
  nil)

(defn list-memories-impl
  "Implementation of list-memories protocol method."
  [store scope scope-id]
  (let [memories @(:memories store)]
    (->> (vals memories)
         (filter (fn [m]
                   (let [meta (get-metadata-impl m)]
                     (and (= scope (:memory/scope meta))
                          (= scope-id (:scope-id meta)))))))))
