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

(ns ai.miniforge.dag-executor.protocols.impl.runtime.images-test
  "Tests for the runtime image registry — EDN load, default lookup, and the
   N11-delta §5 fully-qualified-reference invariant."
  (:require
   [clojure.string]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.images :as images]))

(def ^:private fully-qualified-image-pattern
  "Acceptable shape for default image references per N11-delta §5:
   `<registry>/<repo>:<tag>` or `<registry>/<repo>@sha256:<digest>`.
   The leading registry segment must contain a dot (e.g. `docker.io`)."
  #"^[a-z0-9.-]+\.[a-z0-9.-]+/[^/]+(/[^/]+)*(:[^@:/]+|@sha256:[a-f0-9]{64})$")

(deftest images-default-image-fully-qualified-test
  (testing "default image reference is fully qualified per N11-delta §5"
    (let [ref (images/default-image)]
      (is (string? ref))
      (is (re-matches fully-qualified-image-pattern ref)
          (str "Default image is not fully qualified: " ref)))))

(deftest images-task-runner-images-fully-qualified-test
  (testing "every task-runner image reference is fully qualified"
    (doseq [[k entry] (images/task-runner-images)]
      (testing (str "image " k)
        (is (re-matches fully-qualified-image-pattern (:image entry))
            (str "Image " k " is not fully qualified: " (:image entry)))))))

(deftest images-task-runner-images-excludes-default-test
  (testing "task-runner-images returns only buildable runners (no :default)"
    (let [keys (set (keys (images/task-runner-images)))]
      (is (not (contains? keys :default)))
      (is (contains? keys :minimal))
      (is (contains? keys :clojure)))))

(deftest images-image-lookup-test
  (testing "image lookup returns the registered reference"
    (is (clojure.string/includes? (images/image :minimal) "task-runner"))
    (is (clojure.string/includes? (images/image :clojure) "task-runner-clojure"))
    (is (nil? (images/image :unknown-image)))))

(deftest images-dockerfile-lookup-test
  (testing "dockerfile lookup returns the bundled resource path"
    (is (clojure.string/ends-with? (images/dockerfile :minimal)
                                   "Dockerfile.task-runner"))
    (is (clojure.string/ends-with? (images/dockerfile :clojure)
                                   "Dockerfile.task-runner-clojure"))
    (is (nil? (images/dockerfile :default))
        "Default entry has no Dockerfile — it is a base image, not a builder")))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.dag-executor.protocols.impl.runtime.images-test)
  :leave-this-here)
