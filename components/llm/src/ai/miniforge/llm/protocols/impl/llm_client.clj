(ns ai.miniforge.llm.protocols.impl.llm-client
  "Implementation functions for LLMClient protocol.

   These functions implement the logic for the CLIClient record."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
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
   - :copilot - GitHub Copilot CLI (when available)

   Backend configuration keys:
   - :cmd - Command to execute
   - :args-fn - Function to build args from request map
   - :streaming? - (optional) true if backend supports streaming
   - :stream-parser - (optional) Function to parse stream lines: (fn [line] -> {:delta \"...\" :done? bool})"
  {:claude {:cmd "claude"
            :streaming? true
            :stream-parser (fn [line]
                            ;; Parse stream-json format from claude CLI
                            ;; Format: {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}}
                            (try
                              (when-not (str/blank? line)
                                (let [data (json/parse-string line true)]
                                  (when (= "stream_event" (:type data))
                                    (when-let [delta-text (get-in data [:event :delta :text])]
                                      {:delta delta-text :done? false}))))
                              (catch Exception _e
                                nil)))
            :args-fn (fn [{:keys [prompt system max-tokens streaming?]}]
                       ;; claude CLI: -p/--print for non-interactive mode
                       ;; prompt is positional argument
                       ;; --system-prompt for system context
                       ;; --output-format=stream-json for streaming (requires --verbose)
                       (cond-> ["-p"]
                         streaming? (conj "--output-format" "stream-json")
                         streaming? (conj "--include-partial-messages")
                         streaming? (conj "--verbose")
                         system (into ["--system-prompt" system])
                         max-tokens (into ["--max-budget-usd" "0.10"])
                         true (conj prompt)))}

   :echo   {:cmd "echo"
            :streaming? false  ; echo doesn't support streaming
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

;------------------------------------------------------------------------------ Layer 1.5
;; Execution helper functions

(defn stream-exec-fn
  "Execute command and stream output line by line, calling on-line for each.

   Arguments:
   - cmd: Command vector
   - on-line: Callback (fn [line]) called for each line of output

   Returns: Same format as default-exec-fn when complete"
  [cmd on-line]
  (let [process (apply p/process {:err :string} cmd)
        out-lines (atom [])
        out-reader (java.io.BufferedReader.
                    (java.io.InputStreamReader. (:out process)))]
    ;; Read and callback each line
    (loop []
      (when-let [line (.readLine out-reader)]
        (swap! out-lines conj line)
        (on-line line)
        (recur)))
    ;; Wait for process to complete
    (let [result @process]
      {:out (str/join "\n" @out-lines)
       :err (:err result)
       :exit (:exit result)})))

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

(defn complete-stream-impl
  "Implementation of complete-stream* protocol method for CLIClient.

   Falls back to non-streaming if backend doesn't support it."
  [client request on-chunk]
  (let [config (:config client)
        logger (:logger client)
        {:keys [backend]} config
        backend-config (get backends backend)
        {:keys [cmd args-fn streaming? stream-parser]} backend-config]
    (if-not streaming?
      ;; Backend doesn't support streaming - fall back to complete-impl
      ;; and call on-chunk once with full result
      (let [result (complete-impl client request)]
        (when (:success result)
          (on-chunk {:delta (:content result)
                     :done? true
                     :content (:content result)}))
        result)
      ;; Backend supports streaming
      (let [prompt (or (:prompt request)
                       (build-messages-prompt (:messages request)))
            args (args-fn (assoc request :prompt prompt :streaming? true))
            full-cmd (into [cmd] args)
            accumulated-content (atom "")]
        (when logger
          (log/debug logger :system :agent/streaming-prompt-sent
                     {:data {:backend backend
                             :prompt-length (count prompt)}}))
        ;; Stream the output
        (let [result (stream-exec-fn
                      full-cmd
                      (fn [line]
                        (when-let [parsed (stream-parser line)]
                          (when-let [delta (:delta parsed)]
                            (swap! accumulated-content str delta)
                            (on-chunk (assoc parsed :content @accumulated-content))))))
              exit-code (:exit result)]
          ;; Send final chunk
          (on-chunk {:delta ""
                     :done? true
                     :content @accumulated-content})
          (when logger
            (log/debug logger :system :agent/streaming-complete
                       {:data {:response-length (count @accumulated-content)}}))
          ;; Return same format as complete-impl
          (if (zero? exit-code)
            {:success true
             :content @accumulated-content
             :usage {:input-tokens nil
                     :output-tokens nil}
             :exit-code exit-code}
            {:success false
             :error {:type "cli_error"
                     :message (:err result)}
             :exit-code exit-code}))))))

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
