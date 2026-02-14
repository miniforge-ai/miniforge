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

(ns ai.miniforge.tui-views.update.command
  "Command mode routing.

   Parses :-prefixed command strings and dispatches to model-modifying
   functions. Pure: (model, cmd-str) -> model'.
   Layer 3."
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-views.model :as model]))

;------------------------------------------------------------------------------ Layer 0
;; Command parsing

(defn- parse-command
  "Parse a command string into [cmd-name args-string].
   Strips leading ':' prefix."
  [cmd-str]
  (let [trimmed (str/trim (if (str/starts-with? (or cmd-str "") ":")
                            (subs cmd-str 1)
                            (or cmd-str "")))]
    (let [parts (str/split trimmed #"\s+" 2)]
      [(first parts) (second parts)])))

;------------------------------------------------------------------------------ Layer 1
;; Command handlers

(defn- cmd-quit [model _args]
  (assoc model :quit? true))

(defn- cmd-view [model args]
  (if (str/blank? args)
    (assoc model :flash-message
           (str "Views: " (str/join ", " (map name model/views))))
    (let [view-kw (keyword args)]
      (if (some #{view-kw} model/views)
        (assoc model :view view-kw :selected-idx 0 :scroll-offset 0)
        (assoc model :flash-message (str "Unknown view: " args))))))

(defn- cmd-refresh [model _args]
  (assoc model :flash-message "Refreshed" :last-updated (java.util.Date.)))

(defn- cmd-help [model _args]
  (assoc model :help-visible? true))

(defn- cmd-theme [model args]
  (if (str/blank? args)
    (assoc model :flash-message (str "Current theme: " (name (:theme model))))
    (assoc model :theme (keyword args)
           :flash-message (str "Theme: " args))))

;------------------------------------------------------------------------------ Layer 2
;; Command table and dispatch

(def ^:private commands
  {"q"       {:handler cmd-quit    :help "Quit the TUI"}
   "quit"    {:handler cmd-quit    :help "Quit the TUI"}
   "view"    {:handler cmd-view    :help "Switch to view (e.g. :view evidence)"}
   "refresh" {:handler cmd-refresh :help "Refresh data"}
   "help"    {:handler cmd-help    :help "Show help overlay"}
   "theme"   {:handler cmd-theme   :help "Switch theme (e.g. :theme dark)"}})

(defn execute-command
  "Execute a command string. Returns updated model.
   Pure: (model, cmd-str) -> model'."
  [model cmd-str]
  (let [[cmd-name args] (parse-command cmd-str)]
    (if-let [{:keys [handler]} (get commands cmd-name)]
      (handler model args)
      (assoc model :flash-message (str "Unknown command: " cmd-name)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m (model/init-model))
  (execute-command m ":q")
  (execute-command m ":view evidence")
  (execute-command m ":unknown")
  :leave-this-here)
