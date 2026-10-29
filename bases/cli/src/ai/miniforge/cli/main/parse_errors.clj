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
(ns ai.miniforge.cli.main.parse-errors
  "How `-main` answers babashka.cli dispatch errors. An unknown command
   prints the error and the commands available there on stderr and exits
   2. A command group named without a subcommand (`mf config`,
   `mf fleet --help`) lists its subcommands.

   Layer 0: the unknown-command report and the group listing
   Layer 1: the help-only handler wrapper"
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} handle-unknown-command!
  "Report an unrecognized command on stderr, list the commands available
   where it was typed (when known), and exit 2."
  [{:keys [wrong-input dispatch all-commands]}]
  (binding [*out* *err*]
    (display/print-error (messages/t :main/unknown-command
                                     {:command (str/join " " (conj (vec dispatch) wrong-input))}))
    (println)
    (when (and (= (first dispatch) "fleet")
               (contains? #{"web" "dashboard" "tui"} wrong-input))
      (println (messages/t :main/did-you-mean))
      (println (str "  " (app-config/command-string (if (= wrong-input "dashboard") "web" wrong-input))))
      (println))
    (when (seq all-commands)
      (println (messages/t :main/available-commands
                           {:scope (if (seq dispatch)
                                     (str "'" (str/join " " dispatch) "' ")
                                     "")}))
      (doseq [cmd all-commands]
        (println (str "  " (app-config/command-string (str/join " " (conj (vec dispatch) cmd))))))
      (println))
    (println (messages/t :main/run-help
                         {:command (app-config/command-string "help")})))
  (shared/exit! 2))

(defn ^{:stratum 0} print-group-usage!
  "List the subcommands of the group `dispatch` names. Reached when a
   group is named without a subcommand; that is a help request, so the
   exit stays 0."
  [{:keys [dispatch all-commands]}]
  (println (messages/t :main/usage
                       {:line (apply app-config/command-string
                                     (concat dispatch ["<subcommand>" "[options]"]))}))
  (println)
  (println (messages/t :main/available-commands
                       {:scope (str "'" (str/join " " dispatch) "' ")}))
  (doseq [cmd (sort all-commands)]
    (println (str "  " (apply app-config/command-string (concat dispatch [cmd])))))
  (println)
  (println (messages/t :main/run-help {:command (app-config/command-string "help")})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} help-unless-unknown
  "Wrap `help-fn`, the handler of an entry that only prints help (the bare
   binary, a command group). babashka.cli hands such an entry any word
   typed after it; that word is an unknown command, not a help request."
  [help-fn]
  (fn [{:keys [args dispatch] :as m}]
    (if-let [wrong-input (first args)]
      (handle-unknown-command! {:wrong-input wrong-input :dispatch dispatch})
      (help-fn m))))
