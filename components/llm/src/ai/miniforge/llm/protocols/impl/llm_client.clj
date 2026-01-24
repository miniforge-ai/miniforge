(ns ai.miniforge.llm.protocols.impl.llm-client
  "Implementation functions for LLMClient protocol.

   These functions implement the logic for the CLIClient record."
  (:require
   [clojure.string :as str]
   [babashka.process :as p]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Backend configuration

(def backends
  "Available CLI backends.

   Supported backends:
   - :claude - Claude Code CLI (claude command)
   - :echo   - Echo backend for testing (echoes the prompt)

   Future backends (not yet implemented):
   - :codex  - OpenAI Codex CLI (when available)
   - :copilot - GitHub Copilot CLI (when available)"
  {:claude {:cmd "claude"
            :args-fn (fn [{:keys [prompt system max-tokens]}]
                       ;; claude CLI: -p/--print for non-interactive mode
                       ;; prompt is positional argument
                       ;; --system-prompt for system context
                       (cond-> ["-p"]
                         system (into ["--system-prompt" system])
                         max-tokens (into ["--max-budget-usd" "0.10"])
                         true (conj prompt)))}

   :echo   {:cmd "echo"
            :args-fn (fn [{:keys [prompt]}]
                       [prompt])
            :doc "Echo backend for testing (just echoes the prompt)"}})

;------------------------------------------------------------------------------ Layer 1
;; Pure functions for building prompts and parsing output

(defn build-messages-prompt
  "Convert a messages array into a single prompt string."
  [messages]
  (->> messages
       (map (fn [{:keys [role content]}]
              (case role
                "user" content
                "assistant" (str "[Assistant]: " content)
                "system" (str "[System]: " content)
                content)))
       (str/join "\n\n")))

(defn parse-cli-output
  "Parse CLI output into a response map."
  [output exit-code]
  (if (zero? exit-code)
    {:success true
     :content (str/trim output)
     :usage {:input-tokens nil
             :output-tokens nil}
     :exit-code exit-code}
    {:success false
     :error {:type "cli_error"
             :message output}
     :exit-code exit-code}))

;------------------------------------------------------------------------------ Layer 2
;; Protocol implementations

(defn complete-impl
  "Implementation of complete* protocol method for CLIClient."
  [client request]
  (let [config (:config client)
        logger (:logger client)
        exec-fn (:exec-fn client)
        {:keys [backend]} config
        backend-config (get backends backend)
        {:keys [cmd args-fn]} backend-config
        prompt (or (:prompt request)
                   (build-messages-prompt (:messages request)))
        args (args-fn (assoc request :prompt prompt))
        full-cmd (into [cmd] args)]
    (when logger
      (log/debug logger :system :agent/prompt-sent
                 {:data {:backend backend
                         :prompt-length (count prompt)}}))
    (let [result (exec-fn full-cmd)
          response (parse-cli-output (:out result) (:exit result))]
      (when logger
        (if (:success response)
          (log/debug logger :system :agent/response-received
                     {:data {:response-length (count (:content response))}})
          (log/error logger :system :agent/task-failed
                     {:message "CLI command failed"
                      :data (:error response)})))
      response)))

(defn get-config-impl
  "Implementation of get-config protocol method."
  [client]
  (:config client))

;------------------------------------------------------------------------------ Layer 3
;; Execution functions

(defn default-exec-fn
  "Default execution function using babashka.process."
  [cmd]
  (let [result (apply p/shell {:out :string :err :string :continue true} cmd)]
    {:out (:out result)
     :err (:err result)
     :exit (:exit result)}))

(defn mock-exec-fn
  "Create a mock execution function for testing.
   Returns a function that returns the given output."
  [output & {:keys [exit] :or {exit 0}}]
  (fn [_cmd]
    {:out output
     :err ""
     :exit exit}))

(defn mock-exec-fn-multi
  "Create a mock execution function that returns different outputs per call."
  [outputs]
  (let [call-count (atom 0)]
    (fn [_cmd]
      (let [idx @call-count
            output (get outputs idx (last outputs))]
        (swap! call-count inc)
        {:out output
         :err ""
         :exit 0}))))
