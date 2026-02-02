(ns ai.miniforge.agent.prompts
  "Agent prompt loading utilities.
   Loads system prompts from EDN resources for configurability."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Prompt loading

(defn load-prompt
  "Load an agent prompt from the resources directory.

   Arguments:
   - agent-type: Keyword identifying the agent (e.g., :implementer, :planner)

   Returns:
   - The system prompt string

   Throws:
   - ExceptionInfo if prompt cannot be loaded"
  [agent-type]
  (let [resource-path (str "prompts/" (name agent-type) ".edn")]
    (if-let [resource (io/resource resource-path)]
      (let [prompt-data (with-open [rdr (io/reader resource)]
                          (edn/read (java.io.PushbackReader. rdr)))]
        (or (:prompt/system prompt-data)
            (throw (ex-info "Prompt data missing :prompt/system key"
                            {:agent-type agent-type
                             :resource-path resource-path
                             :keys (keys prompt-data)}))))
      (throw (ex-info "Could not find prompt resource"
                      {:agent-type agent-type
                       :resource-path resource-path})))))

(defn load-prompt-data
  "Load full prompt data map from resources.

   Arguments:
   - agent-type: Keyword identifying the agent

   Returns:
   - The full prompt data map including :prompt/id, :prompt/version, etc."
  [agent-type]
  (let [resource-path (str "prompts/" (name agent-type) ".edn")]
    (if-let [resource (io/resource resource-path)]
      (with-open [rdr (io/reader resource)]
        (edn/read (java.io.PushbackReader. rdr)))
      (throw (ex-info "Could not find prompt resource"
                      {:agent-type agent-type
                       :resource-path resource-path})))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (load-prompt :implementer)
  (load-prompt :tester)
  (load-prompt :planner)
  (load-prompt :releaser)
  (load-prompt-data :implementer)
  :leave-this-here)
