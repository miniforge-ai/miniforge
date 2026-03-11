(ns ai.miniforge.agent.model
  "Shared agent model policy and configuration helpers."
  (:require
   [ai.miniforge.config.interface :as config]))

;------------------------------------------------------------------------------ Layer 0
;; Canonical role model policy

(def ^:private default-agent-models
  "Fallback model defaults when user config does not specify agent defaults."
  {:thinking "claude-opus-4-6"
   :execution "claude-sonnet-4-6"})

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

(def ^:private configured-agent-models
  "Memoized agent default models from merged config."
  (delay (load-configured-agent-models)))

(defn default-model-for-role
  "Return the configured default model id for an agent role."
  [role]
  (if (thinking-roles role)
    (:thinking @configured-agent-models)
    (:execution @configured-agent-models)))

(defn apply-default-model
  "Ensure a config map carries the configured default model for a role unless
   explicitly overridden."
  [role config]
  (update config :model #(or % (default-model-for-role role))))

(comment
  (default-model-for-role :planner)
  (default-model-for-role :implementer)
  (apply-default-model :tester {:temperature 0.2}))
