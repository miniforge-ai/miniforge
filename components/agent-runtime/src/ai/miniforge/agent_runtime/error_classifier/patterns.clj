(ns ai.miniforge.agent-runtime.error-classifier.patterns
  "Error pattern definitions and matching logic.

   Provides patterns for classifying errors into three categories:
   - :agent-backend - Agent system bugs
   - :task-code - User code errors
   - :external - External service errors")

;;------------------------------------------------------------------------------ Layer 0
;; Pattern definitions

(def agent-backend-patterns
  "Patterns that indicate agent system bugs (Claude Code internal errors)"
  [{:regex #"classifyHandoffIfNeeded is not defined"
    :type :agent-backend
    :vendor "Claude Code"}
   {:regex #"ReferenceError:.*agent.*is not defined"
    :type :agent-backend
    :vendor "Claude Code"}
   {:regex #"TypeError:.*agent\..*is not a function"
    :type :agent-backend
    :vendor "Claude Code"}
   {:regex #"Cannot read property.*of undefined"
    :type :agent-backend
    :vendor "Claude Code"}
   {:regex #"null toolResult received"
    :type :agent-backend
    :vendor "Claude Code"}
   {:regex #"agent is undefined"
    :type :agent-backend
    :vendor "Claude Code"}
   {:regex #"handoff failed for agent"
    :type :agent-backend
    :vendor "Claude Code"}])

(def task-code-patterns
  "Patterns that indicate user code errors"
  [{:regex #"Syntax error"
    :type :task-code
    :vendor "miniforge"}
   {:regex #"Compilation failed"
    :type :task-code
    :vendor "miniforge"}
   {:regex #"linting took.*errors:"
    :type :task-code
    :vendor "miniforge"}
   {:regex #"test suite failed"
    :type :task-code
    :vendor "miniforge"}
   {:regex #"Could not resolve namespace"
    :type :task-code
    :vendor "miniforge"}
   {:regex #"No such file or directory"
    :type :task-code
    :vendor "miniforge"}
   {:regex #"Unexpected token"
    :type :task-code
    :vendor "miniforge"}
   {:regex #"Parse error"
    :type :task-code
    :vendor "miniforge"}])

(def external-patterns
  "Patterns that indicate external service errors"
  [{:regex #"ECONNREFUSED"
    :type :external
    :vendor "External Service"}
   {:regex #"Network error"
    :type :external
    :vendor "External Service"}
   {:regex #"API rate limit exceeded"
    :type :external
    :vendor "External Service"}
   {:regex #"API service unavailable"
    :type :external
    :vendor "External Service"}
   {:regex #"Request timeout"
    :type :external
    :vendor "External Service"}
   {:regex #"502 Bad Gateway"
    :type :external
    :vendor "External Service"}
   {:regex #"503 Service Unavailable"
    :type :external
    :vendor "External Service"}
   {:regex #"Connection refused"
    :type :external
    :vendor "External Service"}
   {:regex #"DNS lookup failed"
    :type :external
    :vendor "External Service"}])

;;------------------------------------------------------------------------------ Layer 1
;; Pattern matching

(defn matches-pattern?
  "Check if error message matches a pattern.

   Arguments:
     error - Error message string
     pattern - Pattern map with :regex key

   Returns: Boolean indicating if pattern matches"
  [error pattern]
  (when error
    (boolean (re-find (:regex pattern) error))))

(defn classify-by-patterns
  "Classify error by matching against pattern lists.

   Arguments:
     message - Error message string

   Returns: Pattern map with :type and :vendor, or default task-code classification"
  [message]
  (let [all-patterns (concat agent-backend-patterns
                             task-code-patterns
                             external-patterns)]
    (or (first (filter #(matches-pattern? message %) all-patterns))
        {:type :task-code
         :vendor "miniforge"})))
