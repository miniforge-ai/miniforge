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

(ns ai.miniforge.heuristic.store
  "Storage backends for heuristics.

   Supports both in-memory and local file-based storage."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; ============================================================================
;; Store Protocol
;; ============================================================================

(defprotocol HeuristicStore
  "Protocol for heuristic storage backends."
  (get-value [this key] "Get a value by key")
  (put-value [this key value] "Put a value at key")
  (list-keys [this] "List all keys in the store")
  (delete-value [this key] "Delete a value by key"))

;; ============================================================================
;; Memory Store Implementation
;; ============================================================================

(defrecord MemoryStore [state]
  HeuristicStore
  (get-value [_ key]
    (get @state key))

  (put-value [_ key value]
    (swap! state assoc key value)
    true)

  (list-keys [_]
    (keys @state))

  (delete-value [_ key]
    (swap! state dissoc key)
    true))

(defn create-memory-store
  "Create an in-memory heuristic store."
  []
  (->MemoryStore (atom {})))

;; ============================================================================
;; Local File Store Implementation
;; ============================================================================

(defn expand-path
  "Expand ~ in file paths to home directory."
  [path]
  (if (str/starts-with? path "~")
    (str (System/getProperty "user.home")
         (subs path 1))
    path))

(defn ensure-directory
  "Ensure a directory exists, creating it if necessary."
  [path]
  (let [dir (io/file path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    dir))

(defn key->filename
  "Convert a key to a safe filename."
  [key]
  (str/replace key "/" "_"))

(defn filename->key
  "Convert a filename back to a key."
  [filename]
  (-> filename
      (str/replace "_" "/")
      (str/replace ".edn" "")))

(defrecord LocalFileStore [base-path]
  HeuristicStore
  (get-value [_ key]
    (let [file (io/file base-path (str (key->filename key) ".edn"))]
      (when (.exists file)
        (edn/read-string (slurp file)))))

  (put-value [_ key value]
    (ensure-directory base-path)
    (let [file (io/file base-path (str (key->filename key) ".edn"))]
      (spit file (pr-str value))
      true))

  (list-keys [_]
    (let [dir (io/file base-path)]
      (if (.exists dir)
        (->> (.listFiles dir)
             (filter #(.isFile %))
             (map #(.getName %))
             (filter #(str/ends-with? % ".edn"))
             (map filename->key))
        [])))

  (delete-value [_ key]
    (let [file (io/file base-path (str (key->filename key) ".edn"))]
      (when (.exists file)
        (.delete file))
      true)))

(defn create-local-store
  "Create a local file-based heuristic store.

   Config:
   {:path \"~/.miniforge/heuristics\"}"
  [config]
  (let [path (expand-path (:path config))]
    (ensure-directory path)
    (->LocalFileStore path)))

;; ============================================================================
;; Store Factory
;; ============================================================================

(defn create-store
  "Create a heuristic store of the specified type.

   store-type: :memory or :local
   config: Store-specific configuration"
  [store-type config]
  (case store-type
    :memory (create-memory-store)
    :local  (create-local-store config)
    (throw (ex-info "Unknown store type" {:type store-type}))))

;; ============================================================================
;; Rich Comment
;; ============================================================================

(comment
  ;; Test memory store
  (def mem-store (create-memory-store))
  (put-value mem-store "test/1.0.0" {:data "test"})
  (get-value mem-store "test/1.0.0")
  (list-keys mem-store)

  ;; Test local file store
  (def local-store (create-local-store {:path "/tmp/test-heuristics"}))
  (put-value local-store "prompt/implementer/1.0.0"
             {:system "You are an implementer"
              :task-template "Implement {{task}}"})
  (get-value local-store "prompt/implementer/1.0.0")
  (list-keys local-store)

  :end)
