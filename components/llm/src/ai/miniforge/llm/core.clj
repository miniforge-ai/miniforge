(ns ai.miniforge.llm.core
  "LLM client implementation using CLI backends.
   Layer 0: Pure functions for building prompts and parsing output
   Layer 1: CLI execution and client protocol"
  (:require
   [babashka.process :as p]
   [clojure.string :as str]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Backend configuration

(def backends
  "Available CLI backends."
  {:claude {:cmd "claude"
            :args-fn (fn [{:keys [prompt system max-tokens]}]
                       (cond-> ["-p" prompt]
                         system (into ["--system" system])
                         max-tokens (into ["--max-tokens" (str max-tokens)])))}

   :cursor {:cmd "cursor"
            :args-fn (fn [{:keys [prompt system]}]
                       (cond-> ["--prompt" prompt]
                         system (into ["--system" system])))}

   :echo   {:cmd "echo"
            :args-fn (fn [{:keys [prompt]}]
                       [prompt])
            :doc "Echo backend for testing (just echoes the prompt)"}})

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
             :output-tokens nil}}
    {:success false
     :error {:type "cli_error"
             :message output}
     :exit-code exit-code}))

;------------------------------------------------------------------------------ Layer 1
;; Client protocol and implementation

(defprotocol LLMClient
  "Protocol for LLM interaction."
  (complete* [this request]
    "Send a completion request, returns result map.")
  (get-config [this]
    "Return client configuration."))

(defrecord CLIClient [config logger exec-fn]
  LLMClient
  (complete* [_this request]
    (let [{:keys [backend]} config
          backend-config (get backends backend)
          {:keys [cmd args-fn]} backend-config
          prompt (or (:prompt request)
                     (build-messages-prompt (:messages request)))
          args (args-fn (assoc request :prompt prompt))
          full-cmd (into [cmd] args)
          _ (when logger
              (log/debug logger :system :agent/prompt-sent
                         {:data {:backend backend
                                 :prompt-length (count prompt)}}))
          result (exec-fn full-cmd)
          response (parse-cli-output (:out result) (:exit result))]
      (when logger
        (if (:success response)
          (log/debug logger :system :agent/response-received
                     {:data {:response-length (count (:content response))}})
          (log/error logger :system :agent/task-failed
                     {:message "CLI command failed"
                      :data (:error response)})))
      response))

  (get-config [_this]
    config))

(defn default-exec-fn
  "Default execution function using babashka.process."
  [cmd]
  (let [result (apply p/shell {:out :string :err :string :continue true} cmd)]
    {:out (:out result)
     :err (:err result)
     :exit (:exit result)}))

(defn create-client
  "Create a new LLM client using a CLI backend.

   Options:
   - :backend - Backend keyword (:claude, :cursor, :echo) - default :claude
   - :logger  - Optional logger for request/response logging
   - :exec-fn - Optional execution function override (for testing)

   Example:
     (create-client)  ; uses claude CLI
     (create-client {:backend :cursor})
     (create-client {:backend :claude :logger my-logger})"
  ([] (create-client {}))
  ([{:keys [backend logger exec-fn] :or {backend :claude}}]
   (when-not (contains? backends backend)
     (throw (ex-info (str "Unknown backend: " backend)
                     {:backend backend
                      :available (keys backends)})))
   (->CLIClient {:backend backend}
                logger
                (or exec-fn default-exec-fn))))

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

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a client with claude CLI
  (def client (create-client))

  ;; Create a client with cursor CLI
  (def client (create-client {:backend :cursor}))

  ;; Make a completion request
  (complete* client
             {:messages [{:role "user" :content "Hello!"}]})

  ;; With system prompt
  (complete* client
             {:system "You are a helpful assistant."
              :messages [{:role "user" :content "What is 2+2?"}]})

  ;; Direct prompt
  (complete* client
             {:prompt "What is 2+2?"})

  ;; Mock client for testing
  (def mock-client
    (create-client {:backend :claude
                    :exec-fn (mock-exec-fn "4")}))

  (complete* mock-client
             {:prompt "What is 2+2?"})
  ;; => {:success true, :content "4", ...}

  ;; Echo backend (for testing without real CLI)
  (def echo-client (create-client {:backend :echo}))
  (complete* echo-client {:prompt "Hello"})
  ;; => {:success true, :content "Hello", ...}

  :leave-this-here)
