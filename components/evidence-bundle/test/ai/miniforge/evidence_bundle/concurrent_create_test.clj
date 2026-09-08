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
(ns ai.miniforge.evidence-bundle.concurrent-create-test
  "Regression test: concurrent create-bundle calls must not lose writes.

   Before the fix, create-bundle used reset! on a snapshot computed at call
   entry — two concurrent calls each snapshot the same empty atom value, and
   whichever reset! runs second silently discards the first bundle.
   The fix uses swap!, which is an atomic compare-and-swap that retries on
   contention, so every concurrent create is preserved."
  (:require
   [clojure.test :refer [deftest is]]
   [ai.miniforge.evidence-bundle.interface.protocols.evidence-bundle :as p]
   [ai.miniforge.evidence-bundle.protocols.records.evidence-bundle :as records]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0

(defn- make-manager
  "Create an EvidenceBundleManager with a nil artifact-store.
   The collector skips artifact queries when artifact-store is nil,
   so this is sufficient for testing the atom-write semantics."
  []
  (records/->EvidenceBundleManager
   (atom {})
   nil
   (log/create-logger {:min-level :warn})))

(deftest concurrent-create-bundle-retains-all-bundles
  (let [manager (make-manager)
        workflow-id (random-uuid)
        n 20
        ;; Fire n concurrent create-bundle calls and collect all returned bundles.
        futures (mapv (fn [_]
                        (future
                          (p/create-bundle manager workflow-id {:workflow-state {}})))
                      (range n))
        bundles (mapv deref futures)]
    ;; Every bundle returned by create-bundle must still be retrievable.
    ;; A single lost write proves the race still exists.
    (doseq [b bundles]
      (is (some? (p/get-bundle manager (:evidence-bundle/id b)))
          (str "bundle " (:evidence-bundle/id b) " was lost under concurrent creates")))))
