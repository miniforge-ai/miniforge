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

(ns ai.miniforge.adapter-claude-code.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.io :as io]
   [ai.miniforge.adapter-claude-code.interface :as sut]
   [ai.miniforge.control-plane-adapter.interface :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(defn- make-temp-dir
  "Create a temp directory that auto-deletes on JVM exit."
  []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "claude-code-test"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit dir)
    dir))

(defn- write-file!
  "Write content to a file inside dir, creating parents as needed."
  [^java.io.File dir relative-path content]
  (let [file (io/file dir relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    file))

(defn- touch-fresh!
  "Set a file's last-modified to now (so it appears 'recently active')."
  [^java.io.File file]
  (.setLastModified file (System/currentTimeMillis))
  file)

(defn- touch-stale!
  "Backdate a file's last-modified beyond the activity threshold."
  [^java.io.File file]
  (.setLastModified file (- (System/currentTimeMillis) (* 10 60 1000))) ;; 10 min
  file)

(defn- agent-record
  [& {:as overrides}]
  (merge {:agent/id          (random-uuid)
          :agent/external-id "test-project"
          :agent/name        "Test Agent"
          :agent/vendor      :claude-code
          :agent/status      :running
          :agent/metadata    {}}
         overrides))

;; A PID that should never be valid (PID 0 is the kernel scheduler on POSIX
;; and `kill -0 0` is implementation-defined; very large unused PIDs are
;; safer for "definitely not alive" assertions).
(def ^:private definitely-dead-pid 99999999)

;------------------------------------------------------------------------------ Layer 1
;; create-adapter / adapter-id

(deftest create-adapter-returns-record-test
  (testing "create-adapter returns a ClaudeCodeAdapter record"
    (let [a (sut/create-adapter)]
      (is (instance? ai.miniforge.adapter_claude_code.interface.ClaudeCodeAdapter a))
      (is (= :claude-code (proto/adapter-id a))))))

(deftest create-adapter-stores-config-test
  (testing "Config map is preserved on the adapter"
    (let [a (sut/create-adapter {:projects-dir "/tmp/example"})]
      (is (= {:projects-dir "/tmp/example"} (:config a))))))

;------------------------------------------------------------------------------ Layer 1
;; discover-agents — exercises discovery/discover-sessions via real filesystem

(deftest discover-agents-returns-empty-when-no-projects-test
  (testing "Empty projects directory yields no agents"
    (let [empty-dir (make-temp-dir)
          adapter (sut/create-adapter)]
      (is (= [] (proto/discover-agents adapter {:projects-dir (.getAbsolutePath empty-dir)}))))))

(deftest discover-agents-finds-recently-active-session-test
  (testing "Project with a fresh sessions/*.jsonl is discovered as an agent"
    (let [base (make-temp-dir)
          ;; Create projects/<project>/sessions/<session>.jsonl with fresh mtime
          session-file (write-file! base "my-proj/sessions/abc.jsonl" "{\"line\":1}\n")
          _ (touch-fresh! session-file)
          adapter (sut/create-adapter)
          agents (proto/discover-agents adapter {:projects-dir (.getAbsolutePath base)})]
      (is (= 1 (count agents)))
      (let [a (first agents)]
        (is (= :claude-code (:agent/vendor a)))
        (is (= "my-proj" (:agent/external-id a)))
        (is (contains? (:agent/capabilities a) :code-generation))
        (is (= (.getAbsolutePath session-file)
               (get-in a [:agent/metadata :session-file])))
        (is (inst? (get-in a [:agent/metadata :last-activity])))))))

(deftest discover-agents-skips-stale-and-pidless-projects-test
  (testing "Project with only a stale session log and no lock is excluded"
    (let [base (make-temp-dir)
          stale-session (write-file! base "old-proj/sessions/x.jsonl" "{}\n")
          _ (touch-stale! stale-session)
          adapter (sut/create-adapter)]
      (is (empty? (proto/discover-agents adapter {:projects-dir (.getAbsolutePath base)}))))))

;------------------------------------------------------------------------------ Layer 1
;; poll-agent-status

(deftest poll-agent-status-returns-running-when-recent-log-test
  (testing "Without PID, recent session-file mtime ⇒ :running"
    (let [base (make-temp-dir)
          session-file (write-file! base "sessions/x.jsonl" "{}\n")
          _ (touch-fresh! session-file)
          adapter (sut/create-adapter)
          rec (agent-record :agent/metadata
                            {:session-file (.getAbsolutePath session-file)})]
      (is (= {:status :running} (proto/poll-agent-status adapter rec))))))

(deftest poll-agent-status-returns-nil-when-stale-and-no-pid-test
  (testing "No PID and stale log ⇒ nil (agent gone)"
    (let [base (make-temp-dir)
          session-file (write-file! base "sessions/x.jsonl" "{}\n")
          _ (touch-stale! session-file)
          adapter (sut/create-adapter)
          rec (agent-record :agent/metadata
                            {:session-file (.getAbsolutePath session-file)})]
      (is (nil? (proto/poll-agent-status adapter rec))))))

(deftest poll-agent-status-returns-nil-when-no-metadata-test
  (testing "No PID and no session-file ⇒ nil"
    (let [adapter (sut/create-adapter)]
      (is (nil? (proto/poll-agent-status adapter (agent-record)))))))

;------------------------------------------------------------------------------ Layer 1
;; deliver-decision

(deftest deliver-decision-writes-edn-file-test
  (testing "Decision is written as <decision-id>.edn under decisions-dir/<agent-id>/"
    (let [base (make-temp-dir)
          adapter (sut/create-adapter {:decisions-dir (.getAbsolutePath base)})
          aid (random-uuid)
          did (random-uuid)
          rec (agent-record :agent/id aid)
          resolution {:decision/id did
                      :decision/resolution "approved"
                      :decision/comment    "looks good"}
          ret (proto/deliver-decision adapter rec resolution)
          expected (io/file base (str aid) (str did ".edn"))]
      (is (true? (:delivered? ret)))
      (is (.exists expected))
      (let [round-trip (read-string (slurp expected))]
        (is (= resolution round-trip))))))

;------------------------------------------------------------------------------ Layer 1
;; send-command

(deftest send-command-without-pid-test
  (testing "send-command returns failure when no PID is available"
    (let [adapter (sut/create-adapter)
          ret (proto/send-command adapter (agent-record) :pause)]
      (is (false? (:success? ret)))
      (is (string? (:error ret))))))

(deftest send-command-unknown-command-test
  (testing "send-command rejects unknown command keywords even when PID is present"
    (let [adapter (sut/create-adapter)
          rec (agent-record :agent/metadata {:pid definitely-dead-pid})
          ret (proto/send-command adapter rec :destroy-the-universe)]
      (is (false? (:success? ret)))
      (is (string? (:error ret))))))

(deftest send-command-known-command-with-pid-shape-test
  (testing "Known command with PID returns a {:success? bool} shape"
    (let [adapter (sut/create-adapter)
          rec (agent-record :agent/metadata {:pid definitely-dead-pid})
          ret (proto/send-command adapter rec :pause)]
      ;; We don't assert true/false because the underlying `kill` may succeed
      ;; or fail depending on the environment; we just assert the shape.
      (is (contains? ret :success?))
      (is (boolean? (:success? ret))))))
