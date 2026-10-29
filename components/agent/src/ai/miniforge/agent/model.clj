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
(ns ai.miniforge.agent.model
  "Shared agent model policy and configuration helpers."
  (:require
   [ai.miniforge.agent.messages :as msg]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.llm.interface :as llm]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Canonical role model policy
(def ^{:stratum 0} ^:private backend-role-models
  "Miniforge's model for each role tier on the backends whose model ids it
   knows. It applies only when the user config names no usable model (see
   `default-model-for-role`).

   A backend missing here gets no model from miniforge, so its own default
   applies: the codex CLI uses its built-in default (miniforge runs it with
   --ignore-user-config, so ~/.codex/config.toml is not read), the cursor
   CLI its own default, and ollama codellama. The HTTP APIs that need a model
   (openai-api, gemini-api, openrouter, openai-compat) fail the call with a
   missing-model error until llm.model is set.

   Thinking uses Claude because non-Claude CLI backends (codex,
   cursor-agent, gh-copilot, etc.) don't yet ship structured plans back
   through their stream parsers. Tracked in
   work/multi-backend-cli-parity.spec.edn."
  {:opencode      {:thinking  "anthropic/claude-opus-4-6"
                   :execution "anthropic/claude-sonnet-4-6"}
   :claude        {:thinking  "claude-opus-4-6"
                   :execution "claude-sonnet-4-6"}
   :anthropic-api {:thinking  "claude-opus-4-6"
                   :execution "claude-sonnet-4-6"}})

(def ^{:stratum 0} ^:private thinking-roles
  "Roles that default to the higher-reasoning model tier."
  #{:planner :architect})

(defonce ^{:stratum 0} ^:private warned-mismatches
  ;; [config path, model, backend] triples already reported, so a skipped
  ;; setting is reported once per process rather than once per agent.
  (atom #{}))

(defn- ^{:stratum 0} model-text
  "`model` as the string a client sends, or nil when it names no model. A
   symbol, which an older `config set` stored for a bare word such as
   gpt-5.4, becomes its text (a CLI argv takes only strings); a blank
   string counts as unset."
  [model]
  (let [text (if (symbol? model) (str model) model)]
    (when-not (and (string? text) (str/blank? text))
      text)))

(defn- ^{:stratum 0} model-id-for-backend
  "Return the form of `model-id` to hand to a client on `backend`.

   A provider-prefixed id such as \"anthropic/claude-sonnet-4-6\" is the
   form routing backends (opencode, openrouter) take. Return the bare id
   after the prefix when `backend` serves the catalog family the id
   belongs to (claude and anthropic-api serve anthropic/ ids). Otherwise
   return `model-id` unchanged."
  [model-id backend]
  (let [bare-id (second (re-matches #"[^/]+/(.+)" model-id))
        served  (get-in llm/backends [backend :catalog-backend])]
    (if (and bare-id served (= served (llm/catalog-backend-for-model model-id)))
      bare-id
      model-id)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} default-model-for-role
  "Return the model id `role` runs with on `backend`, or nil when nothing
   names one, so the backend's own default applies. `backend` defaults to
   the configured llm.backend.

   The planner and architect use the thinking tier, every other role the
   execution tier. First usable match wins:
   1. The tier in the user config, [:agents :default-models :thinking] or
      [:agents :default-models :execution] (MINIFORGE_AGENT_THINKING_MODEL,
      MINIFORGE_AGENT_EXECUTION_MODEL).
   2. llm.model (MINIFORGE_LLM_MODEL).
   3. Miniforge's model for the tier on `backend` (`backend-role-models`).

   The shipped default config sets neither 1 nor 2, so anything found there
   is the user's own setting. A blank setting is unset, and a symbol is
   read as its text (`model-text`). A setting naming a catalog model that `backend` does not serve
   (`llm/backend-serves-model?`), such as the anthropic/claude-sonnet-4-5
   an older `config` command wrote, on codex, is skipped with a warning on
   stderr, once per process; a model the catalog does not know is used as
   given."
  ([role] (default-model-for-role role nil))
  ([role backend]
   (let [cfg        (config/load-merged-config)
         tier       (if (thinking-roles role) :thinking :execution)
         backend    (or backend (get-in cfg [:llm :backend]))
         configured (fn configured [path]
                      (when-let [model (model-text (get-in cfg path))]
                        (if (llm/backend-serves-model? backend model)
                          model
                          (let [seen [path model backend]
                                [before _] (swap-vals! warned-mismatches conj seen)]
                            (when-not (contains? before seen)
                              (binding [*out* *err*]
                                (println (msg/t :model/backend-mismatch
                                                {:key (str/join "." (map name path))
                                                 :model model
                                                 :backend (name backend)}))))
                            nil))))]
     (or (configured [:agents :default-models tier])
         (configured [:llm :model])
         (get-in backend-role-models [backend tier])))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} apply-default-model
  "Ensure a config map carries the configured default model for a role unless
   explicitly overridden."
  [role config]
  (update config :model #(or % (default-model-for-role role))))

(defn ^{:stratum 2} resolve-llm-client-for-role
  "Return the provided LLM client set up with the model for an agent role.

   The backend never changes. The client's backend is the one the user
   configured (or chose for the run), and every role runs on it whatever
   model is selected. The model is the role's model on that backend
   (`default-model-for-role`, which skips a catalog model the backend does
   not serve). When there is none, the client is returned as is, so the
   backend's own default applies.

   Arguments:
     role           - Agent role keyword (:planner, :implementer, etc.)
     provided-client - The shared LLM client from workflow context (may be nil)"
  [role provided-client]
  (when provided-client
    (let [backend  (llm/client-backend provided-client)
          model-id (default-model-for-role role backend)]
      (cond-> provided-client
        model-id (assoc-in [:config :model]
                           (model-id-for-backend model-id backend))))))

(comment
  (default-model-for-role :planner)
  (default-model-for-role :implementer :codex)
  (apply-default-model :tester {:temperature 0.2}))
