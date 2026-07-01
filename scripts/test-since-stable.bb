#!/usr/bin/env bb
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

(require '[babashka.process :as p]
         '[clojure.string :as str]
         '[ai.miniforge.bb-test-runner.interface :as bb-test-runner])

(def ^:private clean-env
  (bb-test-runner/sanitize-git-worktree-env (into {} (System/getenv))))

(defn- sh-string
  [argv]
  (let [{:keys [exit out err]} (apply p/sh (concat [{:out :string
                                                     :err :string
                                                     :env clean-env}]
                                                   argv))]
    (when-not (zero? exit)
      (binding [*out* *err*]
        (when-not (str/blank? err)
          (println err)))
      (throw (ex-info "Command failed."
                      {:argv argv
                       :exit exit
                       :err err})))
    out))

(defn- run-step!
  [{:keys [label argv]}]
  (println (str "▶ " label))
  (let [proc (apply p/process (concat [{:out :inherit
                                        :err :inherit
                                        :env clean-env}]
                                      argv))
        heartbeat-ms (* 1000 (bb-test-runner/heartbeat-seconds clean-env))]
    (loop [elapsed heartbeat-ms]
      (let [result (deref proc heartbeat-ms ::timeout)]
        (if (= ::timeout result)
          (do
            (println (str "  Stable-derived tests still running... "
                          (quot elapsed 1000)
                          "s elapsed"))
            (recur (+ elapsed heartbeat-ms)))
          (:exit result))))))

(defn- parse-error?
  [value]
  (false? (:ok? value)))

(defn- print-parse-error!
  [result]
  (let [{:keys [code message data]} (:error result)]
    (binding [*out* *err*]
      (println (str "Stable-derived test input failed: " (name code)))
      (println message)
      (when (seq data)
        (println (pr-str data))))))

(defn- exit-on-parse-error!
  [result]
  (when (parse-error? result)
    (print-parse-error! result)
    (System/exit 2))
  result)

(defn- stable-tags
  []
  (->> (sh-string (concat ["git" "tag" "-l"]
                          (bb-test-runner/stable-tag-globs)))
       str/split-lines
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- changed-projects-since-stable
  []
  (-> (sh-string (bb-test-runner/changed-projects-since-stable-command))
      bb-test-runner/parse-project-list-output
      exit-on-parse-error!))

(defn- full-suite-step
  []
  {:label "No stable tag found locally; fetch tags or create a stable tag anchor to avoid a full Poly test sweep"
   :argv ["clojure" "-M:poly" "test"]})

(defn- effective-plan
  [parsed-args]
  (let [mode (or (:mode parsed-args) :subset)
        projects (or (seq (:projects parsed-args))
                     (seq (changed-projects-since-stable)))]
    (when (seq projects)
      (bb-test-runner/diagnostic-test-plan
       (assoc parsed-args
              :mode mode
              :projects (vec projects))))))

(defn -main
  [& args]
  (let [parsed-args (exit-on-parse-error!
                     (bb-test-runner/parse-diagnostic-args args))]
    (if-not (bb-test-runner/stable-tags-present? (stable-tags))
      (System/exit (run-step! (full-suite-step)))
      (if-let [plan (effective-plan parsed-args)]
        (do
          (println (:summary plan))
          (doseq [step (:steps plan)]
            (let [exit (run-step! step)]
              (when-not (zero? exit)
                (System/exit exit))))
          (println "✓ Stable-derived test plan passed"))
        (println "✓ No changed or affected projects since stable")))))

(apply -main *command-line-args*)
