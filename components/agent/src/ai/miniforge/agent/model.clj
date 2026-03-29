(ns ai.miniforge.agent.model
  "Shared agent model policy and configuration helpers."
  (:require
   [ai.miniforge.config.interface :as config]))

;------------------------------------------------------------------------------ Layer 0
;; Canonical role model policy

(def ^:private default-agent-models
  "Fallback model defaults when user config does not specify agent defaults."
  {:thinking "gpt-5.4"
   :execution "gpt-5.2-codex"})


(def ^:private thinking-roles
  "Roles that default to the higher-reasoning model tier."
  #{:planner :architect})

(defn- load-configured-agent-models
  "Load agent default models from merged user configuration."
  []
  (let [cfg (config/load-merged-config)]
    {:thinking (or (get-in cfg [:agents :default-models :thinking])
                   (:thinking default-agent-models))
     :execution (or (get-in cfg [:agents :default-models :execution])
                    (get-in cfg [:llm :model])
                    (:execution default-agent-models))}))

(defn default-model-for-role
  "Return the configured default model id for an agent role."
  [role]
  (let [{:keys [thinking execution]} (load-configured-agent-models)]
    (if (thinking-roles role)
      thinking
      execution)))

(defn apply-default-model
  "Ensure a config map carries the configured default model for a role unless
   explicitly overridden."
  [role config]
  (update config :model #(or % (default-model-for-role role))))


(defn resolve-llm-client-for-role
  "Resolve or create an LLM client appropriate for an agent role.
   If the role's default model uses a different backend than the provided client,
   creates a new client with the correct backend.

   Arguments:
     role           - Agent role keyword (:planner, :implementer, etc.)
     provided-client - The shared LLM client from workflow context (may be nil)"
  [role provided-client]
  (let [model-id (default-model-for-role role)
        backend-for-model (requiring-resolve 'ai.miniforge.llm.interface/backend-for-model)
        client-backend (requiring-resolve 'ai.miniforge.llm.interface/client-backend)
        create-client (requiring-resolve 'ai.miniforge.llm.interface/create-client)
        needed-backend (backend-for-model model-id)
        current-backend (client-backend provided-client)]
    (cond
      (nil? provided-client)
      nil

      (= current-backend needed-backend)
      provided-client

      :else
      (create-client {:backend needed-backend}))))


(comment
  (default-model-for-role :planner)
  (default-model-for-role :implementer)
  (apply-default-model :tester {:temperature 0.2}))
