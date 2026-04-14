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

(ns ai.miniforge.cli.main.commands.policy
  "Policy pack commands: list, show, install.

   Delegates to ai.miniforge.policy-pack.interface when available.
   Falls back to classpath resource scanning for list/show when the
   component is not loaded (Babashka-safe)."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- packs-dir []
  (str (app-config/home-dir) "/packs"))

(defn- load-pack-from-resource [pack-id]
  (let [resource-path (str "policy_pack/packs/" pack-id ".pack.edn")]
    (when-let [url (io/resource resource-path)]
      (try (edn/read-string (slurp url))
           (catch Exception _ nil)))))

(defn- load-pack-from-path [pack-path]
  (when (fs/exists? pack-path)
    (try (edn/read-string (slurp (str pack-path)))
         (catch Exception _ nil))))

(defn- installed-pack-files []
  (let [dir (io/file (packs-dir))]
    (if (.exists dir)
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName %) ".pack.edn"))
           (sort-by #(.getName %))
           vec)
      [])))

(defn- builtin-pack-ids
  "Known built-in pack IDs to probe on the classpath."
  []
  ["foundations-1.0.0" "miniforge-standards"])

;------------------------------------------------------------------------------ Layer 1
;; Command implementations

(defn policy-list-cmd
  "List all available policy packs.

   Shows installed packs from ~/.miniforge/packs/ and built-in classpath packs."
  [_opts]
  (println)
  (println (display/style (messages/t :policy/header) :foreground :cyan :bold true))
  (println)
  ;; Try the component interface first
  (let [component-packs (shared/try-resolve-fn 'ai.miniforge.policy-pack.interface/list-packs)]
    (cond
      component-packs
      (if (seq component-packs)
        (doseq [pack component-packs]
          (println (str "  " (display/style (str (get pack :pack/id pack)) :foreground :bold)
                        (when-let [v (:pack/version pack)] (str " v" v))
                        (when-let [d (:pack/description pack)] (str "  —  " d)))))
        (println (messages/t :policy/none)))

      :else
      (do
        ;; Installed packs
        (let [files (installed-pack-files)]
          (if (seq files)
            (do
              (println (display/style (messages/t :policy/installed-header) :foreground :cyan))
              (doseq [f files]
                (let [pack (try (edn/read-string (slurp f)) (catch Exception _ nil))
                      id   (or (some-> (:pack/id pack) name)
                               (subs (.getName f) 0 (- (count (.getName f)) (count ".pack.edn"))))]
                  (println (str "    " id
                                (when-let [v (:pack/version pack)] (str " v" v)))))))
            (println (messages/t :policy/no-installed {:dir (packs-dir)}))))
        ;; Built-in classpath packs
        (let [builtins (filter #(io/resource (str "policy_pack/packs/" % ".pack.edn"))
                               (builtin-pack-ids))]
          (when (seq builtins)
            (println)
            (println (display/style (messages/t :policy/builtin-header) :foreground :cyan))
            (doseq [id builtins]
              (println (messages/t :policy/builtin-entry {:id id}))))))))
  (println))

(defn policy-show-cmd
  "Show rules and metadata for a policy pack.

   Checks installed packs, then classpath resources."
  [opts]
  (let [{:keys [pack-id]} opts]
    (if-not pack-id
      (shared/usage-error! :policy/show-usage "policy show <pack-id>")
      (let [pack (or (load-pack-from-resource pack-id)
                     (load-pack-from-path (str (packs-dir) "/" pack-id ".pack.edn"))
                     (shared/try-resolve-fn 'ai.miniforge.policy-pack.interface/load-pack pack-id))]
        (if-not pack
          (do (display/print-error
               (messages/t :policy/not-found
                          {:id pack-id
                           :command (app-config/command-string "policy list")}))
              (shared/exit! 1))
          (do
            (println)
            (println (display/style (messages/t :policy/show-header
                                               {:id (get pack :pack/id pack-id)})
                                    :foreground :cyan :bold true))
            (when-let [v (:pack/version pack)]
              (println (messages/t :policy/show-version {:value v})))
            (when-let [d (:pack/description pack)]
              (println (messages/t :policy/show-description {:value d})))
            (let [rules (get pack :pack/rules [])]
              (println (messages/t :policy/show-rules-count {:count (count rules)}))
              (println)
              (doseq [rule rules]
                (let [id      (get rule :rule/id "unknown")
                      always? (get rule :rule/always-apply false)
                      desc    (get rule :rule/description)]
                  (println (str "  • " (display/style (str id) :foreground :bold)
                                (when always?
                                  (display/style (messages/t :policy/show-always-tag)
                                                :foreground :yellow))
                                (when desc (str "\n    " desc)))))))
            (println)))))))

(defn policy-install-cmd
  "Install a policy pack from a local .pack.edn file.

   Copies the pack into ~/.miniforge/packs/ so it is picked up
   by `scan` and `policy list`."
  [opts]
  (let [{:keys [path]} opts]
    (if-not path
      (shared/usage-error! :policy/install-usage "policy install <path>")
      (if-not (fs/exists? path)
        (do (display/print-error (messages/t :policy/install-not-found {:path path}))
            (shared/exit! 1))
        (let [pack (try (edn/read-string (slurp (str path)))
                        (catch Exception _e nil))]
          (if-not pack
            (do (display/print-error (messages/t :policy/install-invalid {:path path}))
                (shared/exit! 1))
            (let [dest-dir  (packs-dir)
                  file-name (fs/file-name path)
                  ;; Ensure .pack.edn suffix
                  dest-name (if (str/ends-with? (str file-name) ".pack.edn")
                              (str file-name)
                              (str file-name ".pack.edn"))
                  dest-path (str dest-dir "/" dest-name)]
              (fs/create-dirs dest-dir)
              (fs/copy path dest-path {:replace-existing true})
              (display/print-success (messages/t :policy/install-success {:name dest-name}))
              (println (messages/t :policy/install-location {:path dest-path}))
              (when-let [id (:pack/id pack)]
                (println (messages/t :policy/install-pack-id {:id id}))))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (policy-list-cmd {})
  (policy-show-cmd {:pack-id "foundations-1.0.0"})
  (policy-install-cmd {:path "my-custom.pack.edn"})
  :end)
