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

(ns ai.miniforge.loop.escalation
  "Human escalation for retry budget exhaustion.
   Prompts user for hints when agent is stuck.
   
   Layer 0: Pure functions for prompt formatting
   Layer 1: User interaction functions
   Layer 2: Escalation integration with inner loop"
  (:require
   [clojure.string :as str]
   [ai.miniforge.decision.interface :as decision]
   [ai.miniforge.loop.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Prompt formatting (pure functions)

(defn format-error-entry
  "Format a single error entry for display.
   Reads :anomaly/message and :anomaly/category when available,
   falls back to :message and :code for legacy shapes."
  [idx err]
  (let [anomaly (:anomaly err)
        msg (or (when anomaly (:anomaly/message anomaly))
                (:message err))
        code (or (when anomaly (name (:anomaly/category anomaly)))
                 (when-let [c (:code err)] (name c)))]
    (str "  " (inc idx) ". " msg
         (when code (str " [" code "]")))))

(defn format-error-context
  "Format error context for user display.
   
   Arguments:
   - errors - Vector of error maps
   - iteration - Current iteration number
   - artifact - Last generated artifact
   
   Returns formatted string."
  [errors iteration artifact]
  (str (messages/t :escalation/context-header {:iteration iteration}) "\n\n"
       (messages/t :escalation/errors-label) "\n"
       (str/join "\n"
         (map-indexed format-error-entry errors))
       "\n\n"
       (messages/t :escalation/last-attempt-label) "\n"
       (if-let [content (:artifact/content artifact)]
         (let [preview (if (string? content)
                         (subs content 0 (min 200 (count content)))
                         (pr-str content))]
           (str "  " preview
                (when (> (count (str content)) 200) "...")))
         (str "  " (messages/t :escalation/no-content)))))

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
         (messages/t :escalation/banner-separator) "\n"
         (messages/t :escalation/banner-title) "\n"
         (messages/t :escalation/banner-separator) "\n\n"
         (format-error-context errors iteration artifact)
         "\n\n"
         (messages/t :escalation/termination-reason {:reason (name reason)}) "\n"
         "\n" (messages/t :escalation/options-header) "\n"
         (messages/t :escalation/option-hints) "\n"
         (messages/t :escalation/option-abort) "\n"
         "\n"
         (messages/t :escalation/input-prompt))))

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

(defn create-escalation-checkpoint
  "Create a canonical checkpoint for an inner-loop escalation."
  [loop-state & [opts]]
  (decision/create-loop-escalation-checkpoint loop-state (or opts {})))

(defn- result->response
  [result]
  (case (:action result)
    :continue (decision/decision-response :input (:hints result))
    :abort {:type :reject
            :value :abort
            :rationale (:reason result)
            :authority-role (if (:reason result) :system :human)}
    {:type :defer
     :authority-role :human}))

(defn- escalated-result
  [result checkpoint episode]
  (assoc result
         :escalated true
         :decision/checkpoint checkpoint
         :decision/episode episode))

(defn- abort-escalation
  [reason checkpoint episode]
  {:escalated true
   :action :abort
   :reason reason
   :decision/checkpoint checkpoint
   :decision/episode episode})

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
  (let [escalation-fn (:escalation-fn context)
        checkpoint (create-escalation-checkpoint loop-state (:decision/checkpoint-opts context))
        episode (decision/create-episode checkpoint)]
    (if escalation-fn
      ;; Escalation function available
      (let [result (escalation-fn loop-state :prompt-fn (:prompt-fn context))
            resolved-checkpoint (decision/resolve-checkpoint checkpoint
                                                             (result->response result))
            updated-episode (decision/update-episode episode resolved-checkpoint)]
        (escalated-result result resolved-checkpoint updated-episode))
      ;; No escalation function, default to abort
      (let [result {:action :abort
                    :reason "No escalation function provided"}
            resolved-checkpoint (decision/resolve-checkpoint checkpoint
                                                             (result->response result))
            updated-episode (decision/update-episode episode resolved-checkpoint)]
        (abort-escalation "No escalation function provided"
                          resolved-checkpoint
                          updated-episode)))))

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
