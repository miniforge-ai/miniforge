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
(ns ai.miniforge.agent.hook-end-to-end-test
  "A stub Claude CLI driven through a real artifact session. The stub runs
   each PreToolUse hook command from the session's --settings file the way
   Claude does (through `sh -c`, the request JSON on stdin; exit 2 or a
   deny decision blocks) and writes the file only when every hook allows
   it. The hook is miniforge's real `hook-eval`, started by the command the
   session wrote, so this covers the session settings, the Claude
   arguments, the hook command and the tool policy together."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.artifact-session :as artifact-session]
   [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private env-override?
  "MINIFORGE_CMD makes the session's hook run an installed miniforge rather
   than this checkout's, so the test skips when it is set."
  (some? (System/getenv "MINIFORGE_CMD")))

(defn- ^{:stratum 0} matches-tool?
  "True when a PreToolUse matcher group's `matcher` selects `tool`, as
   Claude reads it: blank or \"*\" selects every tool, anything else is a
   pattern over the tool name."
  [matcher tool]
  (or (contains? #{nil "" "*"} matcher)
      (boolean (re-matches (re-pattern matcher) tool))))

(defn- ^{:stratum 0} decision-of
  "The permissionDecision a hook run printed, or nil."
  [{:keys [out]}]
  (get-in (json/parse-string out) ["hookSpecificOutput" "permissionDecision"]))

(defn- ^{:stratum 0} flag-value
  "The argument after `flag` in `cmd`, or nil."
  [cmd flag]
  (second (drop-while #(not= flag %) cmd)))

(defn- ^{:stratum 0} hook-allows?
  "True when one PreToolUse hook run allows the call: exit 0 and no deny
   decision. Claude blocks on exit 2; any other non-zero exit is a
   non-blocking error, which the stub treats as a refusal so the test
   cannot pass on a broken hook."
  [{:keys [exit out]}]
  (and (zero? exit)
       (not= "deny" (get-in (json/parse-string out) ["hookSpecificOutput" "permissionDecision"]))))

(defn- ^{:stratum 0} delete-tree!
  [^java.io.File dir]
  (run! #(.delete ^java.io.File %) (reverse (file-seq dir))))

(defn- ^{:stratum 0} temp-repo!
  "A fresh git repository to act as the session's worktree."
  []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "hook-e2e" (make-array java.nio.file.attribute.FileAttribute 0)))
        init (shell/sh "git" "init" "-q" (str dir))]
    (when-not (zero? (:exit init))
      (throw (ex-info "git init failed for the test worktree" init)))
    dir))

(defn- ^{:stratum 0} write-through-session!
  "Ask a Claude client whose CLI is `exec-fn`, inside an artifact session
   for `role` on `dir`, to write x.txt."
  [role dir exec-fn]
  (let [client (llm/create-client {:backend :claude :exec-fn exec-fn})]
    (artifact-session/with-session
      {:agent/role role :execution/worktree-path (str dir)}
      (fn [session]
        (llm/chat client "Write x.txt" (artifact-session/session->mcp-opts session 1.0 2))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} stub-claude
  "An exec-fn standing in for the Claude CLI. For one Write of `target`
   it runs every command hook whose matcher selects Write in the
   --settings file and writes the file only when all of them allow it.
   Each call is recorded in `calls`."
  [target calls]
  (fn [cmd & _]
    (let [settings (json/parse-string (slurp (flag-value cmd "--settings")))
          hooks    (for [group (get-in settings ["hooks" "PreToolUse"])
                         :when (matches-tool? (get group "matcher") "Write")
                         hook  (get group "hooks")
                         :when (= "command" (get hook "type"))]
                     (get hook "command"))
          request  (json/generate-string {"hook_event_name" "PreToolUse"
                                          "tool_name" "Write"
                                          "tool_input" {"file_path" (str target) "content" "x"}})
          runs     (mapv #(shell/sh "sh" "-c" % :in request) hooks)
          allowed? (and (seq runs) (every? hook-allows? runs))]
      (swap! calls conj {:cmd cmd :runs runs})
      (when allowed? (spit target "x"))
      {:out (json/generate-string {"type" "result" "subtype" "success"
                                   "result" (if allowed? "wrote" "blocked")})
       :err ""
       :exit 0})))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} denied-tool-is-blocked-end-to-end-test
  (if env-override?
    (println "skipping denied-tool-is-blocked-end-to-end-test: MINIFORGE_CMD is set")
    (do
      (testing "the reviewer's hook answers Write with a deny, so the stub CLI writes nothing"
        (let [dir (temp-repo!)]
          (try
            (let [calls (atom [])]
              (write-through-session! :reviewer dir (stub-claude (io/file dir "x.txt") calls))
              (let [runs (:runs (first @calls))]
                (is (= 1 (count @calls)))
                (is (seq runs) "the session's settings carry a PreToolUse hook that selects Write")
                (is (every? #(and (zero? (:exit %)) (= "deny" (decision-of %))) runs) (pr-str runs))
                (is (not (.exists (io/file dir "x.txt"))))))
            (finally (delete-tree! dir)))))

      (testing "the implementer's hook allows the same Write"
        (let [dir (temp-repo!)]
          (try
            (let [calls (atom [])]
              (write-through-session! :implementer dir (stub-claude (io/file dir "x.txt") calls))
              (is (= 1 (count @calls)))
              (is (.exists (io/file dir "x.txt")) (pr-str (:runs (first @calls)))))
            (finally (delete-tree! dir))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (write-through-session! :reviewer (temp-repo!) (stub-claude (io/file "/tmp/x.txt") (atom [])))
  :end)
