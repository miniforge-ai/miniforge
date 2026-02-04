(ns ai.miniforge.llm.protocols.impl.llm-client
  "Implementation functions for LLMClient protocol.

   These functions implement the logic for the CLIClient record."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [babashka.process :as p]
   [ai.miniforge.logging.interface :as log])
  (:import
   [java.io ByteArrayInputStream]))

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
  "Parse CLI output into a response map.

   For errors, uses stderr if stdout is empty to provide better error context."
  ([output exit-code]
   (parse-cli-output output exit-code nil))
  ([output exit-code stderr]
   (if (zero? exit-code)
     {:success true
      :content (str/trim output)
      :usage {:input-tokens nil
              :output-tokens nil}
      :exit-code exit-code}
     (let [error-message (if (and stderr (str/blank? output))
                           stderr  ; Use stderr if stdout is empty
                           output)]
       {:success false
        :error {:type "cli_error"
                :message (str/trim error-message)
                :stderr stderr
                :stdout output}
        :exit-code exit-code}))))

;------------------------------------------------------------------------------ Layer 1.5
;; Execution helper functions

(defn stream-exec-fn
  "Execute command and stream output line by line, calling on-line for each.

   Arguments:
   - cmd: Command vector
   - on-line: Callback (fn [line]) called for each line of output

   Returns: Same format as default-exec-fn when complete"
  [cmd on-line]
  ;; Use empty ByteArrayInputStream for stdin to prevent CLI from waiting on input
  (let [empty-stdin (ByteArrayInputStream. (byte-array 0))
        process (apply p/process {:err :string :in empty-stdin} cmd)
        out-lines (atom [])
        out-reader (java.io.BufferedReader.
                    (java.io.InputStreamReader. (:out process)))
        timeout-ms 300000  ; 5 minutes total timeout
        line-timeout-ms 60000  ; 1 minute per line timeout
        start-time (System/currentTimeMillis)]
    ;; Read and callback each line with timeout protection
    (loop []
      (let [elapsed (- (System/currentTimeMillis) start-time)]
        (when (< elapsed timeout-ms)
          ;; Use Future with timeout for each readLine call to prevent indefinite blocking
          (let [read-future (future (.readLine out-reader))
                line (try
                       (deref read-future line-timeout-ms nil)
                       (catch java.util.concurrent.TimeoutException _
                         ;; Line read timed out, stop reading
                         nil))]
            (when line
              (swap! out-lines conj line)
              (on-line line)
              (recur))))))
    ;; Wait for process to complete (with timeout to prevent hanging)
    (let [result (deref process timeout-ms {:exit -1 :err "Process timed out after 5 minutes"})]
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
          response (parse-cli-output (:out result) (:exit result) (:err result))]
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
            (let [error-message (or (:err result)
                                    (when (str/blank? @accumulated-content)
                                      "Process failed with no output")
                                    "Process failed")]
              {:success false
               :error {:type "cli_error"
                       :message (str/trim error-message)
                       :stderr (:err result)
                       :stdout @accumulated-content}
               :exit-code exit-code})))))))

(defn get-config-impl
  "Implementation of get-config protocol method."
  [client]
  (:config client))

;------------------------------------------------------------------------------ Layer 3
;; Execution functions

(defn default-exec-fn
  "Default execution function using babashka.process/shell.
   Uses shell instead of process to avoid stdin hanging issues."
  [cmd]
  ;; p/shell handles stdin properly for non-interactive commands
  ;; :continue true prevents throwing on non-zero exit
  (let [timeout-ms 300000  ; 5 minutes
        result (apply p/shell {:out :string :err :string :continue true :timeout timeout-ms} cmd)]
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
