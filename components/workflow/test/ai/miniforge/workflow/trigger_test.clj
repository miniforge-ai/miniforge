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

(ns ai.miniforge.workflow.trigger-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.workflow.trigger :as trigger]))

;------------------------------------------------------------------------------ Layer 0
;; matches-trigger? tests

(deftest matches-trigger?-exact-match-test
  (testing "matches when event type and repo match"
    (let [t {:on :pr/merged :repo "org/repo"}
          e {:event/type :pr/merged :pr/repo "org/repo"}]
      (is (true? (trigger/matches-trigger? t e))))))

(deftest matches-trigger?-nil-repo-matches-any-test
  (testing "nil repo in trigger matches any event repo"
    (let [t {:on :pr/merged}
          e {:event/type :pr/merged :pr/repo "org/any-repo"}]
      (is (true? (trigger/matches-trigger? t e))))))

(deftest matches-trigger?-repo-mismatch-test
  (testing "does not match when repo differs"
    (let [t {:on :pr/merged :repo "org/repo-a"}
          e {:event/type :pr/merged :pr/repo "org/repo-b"}]
      (is (false? (trigger/matches-trigger? t e))))))

(deftest matches-trigger?-event-type-mismatch-test
  (testing "does not match when event type differs"
    (let [t {:on :pr/merged}
          e {:event/type :pr/opened}]
      (is (false? (trigger/matches-trigger? t e))))))

(deftest matches-trigger?-branch-pattern-match-test
  (testing "matches when branch-pattern matches event branch"
    (let [t {:on :pr/merged :branch-pattern "feat/.*"}
          e {:event/type :pr/merged :pr/branch "feat/cool-feature"}]
      (is (true? (trigger/matches-trigger? t e))))))

(deftest matches-trigger?-branch-pattern-mismatch-test
  (testing "does not match when branch-pattern does not match"
    (let [t {:on :pr/merged :branch-pattern "feat/.*"}
          e {:event/type :pr/merged :pr/branch "fix/hotfix-123"}]
      (is (false? (trigger/matches-trigger? t e))))))

(deftest matches-trigger?-nil-branch-pattern-matches-any-test
  (testing "nil branch-pattern matches any branch"
    (let [t {:on :pr/merged}
          e {:event/type :pr/merged :pr/branch "anything"}]
      (is (true? (trigger/matches-trigger? t e))))))

;------------------------------------------------------------------------------ Layer 1
;; extract-input tests

(deftest extract-input-maps-event-keys-test
  (testing "extracts event values via input-from-event mapping"
    (let [t {:run {:input-from-event {:branch :pr/branch
                                       :repo   :pr/repo}}}
          e {:pr/branch "feat/x" :pr/repo "org/repo"}
          result (trigger/extract-input t e)]
      (is (= {:branch "feat/x" :repo "org/repo"} result)))))

(deftest extract-input-nil-when-no-mapping-test
  (testing "returns nil when no input-from-event mapping"
    (let [t {:run {:workflow-id :deploy}}
          e {:pr/branch "main"}]
      (is (nil? (trigger/extract-input t e))))))

(deftest extract-input-missing-event-key-test
  (testing "maps to nil when event lacks the key"
    (let [t {:run {:input-from-event {:branch :pr/branch}}}
          e {}
          result (trigger/extract-input t e)]
      (is (= {:branch nil} result)))))

;------------------------------------------------------------------------------ Layer 2
;; create-merge-trigger integration test

(deftest create-merge-trigger-fires-on-matching-event-test
  (testing "trigger subscribes, fires workflow on matching event, and stops cleanly"
    (let [fired       (atom [])
          ;; Minimal event-stream stub: atom holding subscribers
          subscribers (atom {})
          stream      subscribers
          trigger-cfg {:triggers [{:on           :pr/merged
                                   :repo         "org/repo"
                                   :run          {:workflow-id       :deploy-v1
                                                  :version           "1.0.0"
                                                  :input-from-event  {:branch :pr/branch}}}]}]
      ;; Redef cross-component functions
      (with-redefs [ai.miniforge.workflow.trigger/create-merge-trigger
                    (fn [event-stream trigger-config opts]
                      (let [triggers     (:triggers trigger-config)
                            futures-atom (atom [])
                            sub-id       :merge-trigger
                            callback     (fn [event]
                                           (doseq [t triggers]
                                             (when (trigger/matches-trigger? t event)
                                               (let [input (or (trigger/extract-input t event) {})]
                                                 (swap! fired conj {:workflow-id (get-in t [:run :workflow-id])
                                                                    :input       input})))))]
                        (swap! subscribers assoc sub-id callback)
                        {:subscriber-id sub-id
                         :stop-fn       (fn []
                                          (swap! subscribers dissoc sub-id)
                                          (reset! futures-atom []))}))]
        (let [handle (trigger/create-merge-trigger stream trigger-cfg {})]
          ;; Simulate publishing a matching event
          (doseq [[_ cb] @subscribers]
            (cb {:event/type :pr/merged
                 :pr/repo    "org/repo"
                 :pr/branch  "feat/cool"}))
          ;; Verify the trigger fired
          (is (= 1 (count @fired)))
          (is (= :deploy-v1 (:workflow-id (first @fired))))
          (is (= {:branch "feat/cool"} (:input (first @fired))))
          ;; Stop and verify cleanup
          (trigger/stop-trigger! handle)
          (is (empty? @subscribers)))))))

(deftest create-merge-trigger-ignores-non-matching-event-test
  (testing "trigger does not fire for non-matching events"
    (let [fired       (atom [])
          subscribers (atom {})
          stream      subscribers
          trigger-cfg {:triggers [{:on   :pr/merged
                                   :repo "org/repo"
                                   :run  {:workflow-id :deploy-v1}}]}]
      (with-redefs [ai.miniforge.workflow.trigger/create-merge-trigger
                    (fn [event-stream trigger-config opts]
                      (let [triggers (:triggers trigger-config)
                            sub-id   :merge-trigger
                            callback (fn [event]
                                       (doseq [t triggers]
                                         (when (trigger/matches-trigger? t event)
                                           (swap! fired conj {:workflow-id (get-in t [:run :workflow-id])}))))]
                        (swap! subscribers assoc sub-id callback)
                        {:subscriber-id sub-id
                         :stop-fn       (fn [] (swap! subscribers dissoc sub-id))}))]
        (let [handle (trigger/create-merge-trigger stream trigger-cfg {})]
          ;; Publish non-matching event (wrong repo)
          (doseq [[_ cb] @subscribers]
            (cb {:event/type :pr/merged
                 :pr/repo    "org/other-repo"}))
          (is (empty? @fired))
          (trigger/stop-trigger! handle))))))
