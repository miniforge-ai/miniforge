;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.tui-views.command
  "Command mode parser and executor.

   Commands are entered with the : prefix (vim-style).
   Supported commands:
   - :workflows [filter]    - Show/filter workflow list
   - :evidence              - Switch to evidence view
   - :artifacts             - Switch to artifact browser
   - :dag                   - Switch to DAG kanban
   - :help                  - Show available commands
   - :quit / :q             - Exit TUI"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Command parsing

(defn parse-command
  "Parse a command string (without the leading :) into a command map.

   Returns {:command kw :args [str]} or nil if unparseable.

   Examples:
     (parse-command \"workflows\")
     ;; => {:command :workflows :args []}

     (parse-command \"workflows status:blocked\")
     ;; => {:command :workflows :args [\"status:blocked\"]}"
  [input]
  (when (seq input)
    (let [parts (str/split (str/trim input) #"\s+")
          cmd (first parts)
          args (rest parts)]
      (when cmd
        {:command (keyword cmd)
         :args (vec args)}))))

;------------------------------------------------------------------------------ Layer 1
;; Command execution (returns model updates)

(defn execute-command
  "Execute a parsed command against the model.
   Returns an updated model.

   This is a pure function -- no side effects."
  [model {:keys [command args]}]
  (case command
    :workflows
    (let [filter-str (first args)]
      (cond-> (assoc model :view :workflow-list :selected-idx 0)
        (and filter-str (str/includes? filter-str "status:"))
        (assoc :flash-message (str "Filter: " filter-str))))

    :evidence
    (assoc model :view :evidence :selected-idx 0)

    :artifacts
    (assoc model :view :artifact-browser :selected-idx 0)

    :dag
    (assoc model :view :dag-kanban :selected-idx 0)

    (:quit :q)
    (assoc model :quit? true)

    :help
    (assoc model :flash-message "Commands: :workflows :evidence :artifacts :dag :quit :help")

    ;; Unknown command
    (assoc model :flash-message (str "Unknown command: " (name command)))))

;------------------------------------------------------------------------------ Layer 2
;; Integration with update loop

(defn handle-command-submit
  "Handle command mode Enter key. Parse and execute the command buffer.
   Returns updated model with mode reset to :normal."
  [model]
  (let [buf (:command-buf model)
        ;; Strip leading ':'
        input (if (str/starts-with? buf ":")
                (subs buf 1)
                buf)
        parsed (parse-command input)]
    (if parsed
      (-> model
          (execute-command parsed)
          (assoc :mode :normal :command-buf ""))
      (assoc model
             :mode :normal
             :command-buf ""
             :flash-message "Invalid command"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (parse-command "workflows")
  ;; => {:command :workflows :args []}

  (parse-command "workflows status:blocked")
  ;; => {:command :workflows :args ["status:blocked"]}

  (parse-command "quit")
  ;; => {:command :quit :args []}

  :leave-this-here)
