(ns ai.miniforge.loop.escalation
  "Human escalation for retry budget exhaustion.
   Prompts user for hints when agent is stuck.
   
   Layer 0: Pure functions for prompt formatting
   Layer 1: User interaction functions
   Layer 2: Escalation integration with inner loop"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Prompt formatting (pure functions)

(defn format-error-context
  "Format error context for user display.
   
   Arguments:
   - errors - Vector of error maps
   - iteration - Current iteration number
   - artifact - Last generated artifact
   
   Returns formatted string."
  [errors iteration artifact]
  (str "After " iteration " attempts, validation failed:\n\n"
       "Errors:\n"
       (str/join "\n"
         (map-indexed
          (fn [idx err]
            (str "  " (inc idx) ". " (:message err)
                 (when-let [code (:code err)]
                   (str " [" (name code) "]"))))
          errors))
       "\n\n"
       "Last attempt:\n"
       (if-let [content (:artifact/content artifact)]
         (let [preview (if (string? content)
                         (subs content 0 (min 200 (count content)))
                         (pr-str content))]
           (str "  " preview
                (when (> (count (str content)) 200) "...")))
         "  (no content)")))

(defn format-escalation-prompt
  "Format the escalation prompt for user.
   
   Arguments:
   - loop-state - Current inner loop state
   
   Returns formatted prompt string."
  [loop-state]
  (let [errors (:loop/errors loop-state)
        iteration (:loop/iteration loop-state)
        artifact (:loop/artifact loop-state)
        reason (get-in loop-state [:loop/termination :reason])]
    (str "\n"
         "=================================================================\n"
         "AGENT ESCALATION\n"
         "=================================================================\n\n"
         (format-error-context errors iteration artifact)
         "\n\n"
         "Termination reason: " (name reason) "\n"
         "\nOptions:\n"
         "  1. Provide hints to help the agent (enter text)\n"
         "  2. Abort this task (type 'abort')\n"
         "\n"
         "Your input: ")))

;------------------------------------------------------------------------------ Layer 1
;; User interaction

(defn read-user-input
  "Read a line of input from user. Can be mocked in tests."
  []
  (read-line))

(defn prompt-user
  "Prompt user for input via stdin.
   
   Arguments:
   - prompt-text - Text to display to user
   
   Returns:
   {:type :hints :content string} or
   {:type :abort}"
  [prompt-text]
  (print prompt-text)
  (flush)
  (let [input (str/trim (read-user-input))]
    (if (or (= input "abort")
            (= input "ABORT")
            (str/starts-with? input "abort"))
      {:type :abort}
      {:type :hints
       :content input})))

(defn escalate-to-user
  "Escalate to user for guidance.
   
   Arguments:
   - loop-state - Current inner loop state
   - & opts - Keyword options:
     :prompt-fn - Custom prompt function (default: prompt-user)
   
   Returns:
   {:action :continue :hints string} or
   {:action :abort}"
  [loop-state & {:keys [prompt-fn]}]
  (let [actual-prompt-fn (or prompt-fn prompt-user)
        prompt-text (format-escalation-prompt loop-state)
        user-response (actual-prompt-fn prompt-text)]
    (case (:type user-response)
      :abort
      {:action :abort}
      
      :hints
      {:action :continue
       :hints (:content user-response)}
      
      ;; Default to abort if unknown response
      {:action :abort})))

;------------------------------------------------------------------------------ Layer 2
;; Inner loop integration

(defn handle-escalation
  "Handle escalation in inner loop.
   Called when loop reaches :escalated state.
   
   Arguments:
   - loop-state - Escalated loop state
   - context - Context map with optional :escalation-fn
   
   Returns:
   {:escalated true :action :abort} or
   {:escalated true :action :continue :hints string}"
  [loop-state context]
  (let [escalation-fn (:escalation-fn context)]
    (if escalation-fn
      ;; Escalation function available
      (let [result (escalation-fn loop-state :prompt-fn (:prompt-fn context))]
        (assoc result :escalated true))
      ;; No escalation function, default to abort
      {:escalated true
       :action :abort
       :reason "No escalation function provided"})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create mock loop state
  (def mock-loop-state
    {:loop/id (random-uuid)
     :loop/state :escalated
     :loop/iteration 5
     :loop/errors [{:code :syntax-error
                    :message "Parse error on line 10"}
                   {:code :lint-error
                    :message "Unused variable 'x'"}]
     :loop/artifact {:artifact/id (random-uuid)
                     :artifact/type :code
                     :artifact/content "(defn broken [\n  incomplete"}
     :loop/termination {:reason :max-iterations}})

  ;; Format prompt
  (println (format-escalation-prompt mock-loop-state))

  ;; Mock prompt function that always provides hints
  (defn mock-prompt-hints [_prompt]
    {:type :hints
     :content "Try adding a closing bracket"})

  ;; Test escalation with hints
  (escalate-to-user mock-loop-state :prompt-fn mock-prompt-hints)
  ;; => {:action :continue :hints "Try adding a closing bracket"}

  ;; Mock prompt function that aborts
  (defn mock-prompt-abort [_prompt]
    {:type :abort})

  ;; Test escalation with abort
  (escalate-to-user mock-loop-state :prompt-fn mock-prompt-abort)
  ;; => {:action :abort}

  ;; Handle escalation
  (handle-escalation mock-loop-state
                     {:escalation-fn escalate-to-user
                      :prompt-fn mock-prompt-hints})
  ;; => {:escalated true :action :continue :hints "..."}

  :leave-this-here)
