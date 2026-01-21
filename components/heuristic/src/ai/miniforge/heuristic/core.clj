;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.heuristic.core
  "Core heuristic management logic.

   Layer 0: Pure functions for heuristic operations
   Layer 1: Coordinated store operations"
  (:require
   [ai.miniforge.heuristic.store :as store]
   [clojure.string :as str]))

;; ============================================================================
;; Layer 0 - Pure Functions
;; ============================================================================

(defn parse-version
  "Parse a semantic version string into components.

   Returns {:major int :minor int :patch int}"
  [version-str]
  (when version-str
    (let [[major minor patch] (map #(Integer/parseInt %)
                                   (str/split version-str #"\."))]
      {:major (or major 0)
       :minor (or minor 0)
       :patch (or patch 0)})))

(defn compare-versions
  "Compare two version strings.

   Returns: -1 if v1 < v2, 0 if v1 = v2, 1 if v1 > v2"
  [v1 v2]
  (let [p1 (parse-version v1)
        p2 (parse-version v2)]
    (cond
      (not= (:major p1) (:major p2)) (compare (:major p1) (:major p2))
      (not= (:minor p1) (:minor p2)) (compare (:minor p1) (:minor p2))
      :else (compare (:patch p1) (:patch p2)))))

(defn sort-versions
  "Sort version strings newest first."
  [versions]
  (sort (fn [a b] (compare-versions b a)) versions))

(defn build-heuristic-key
  "Build a storage key for a heuristic."
  [heuristic-type version]
  (str (name heuristic-type) "/" version))

;; ============================================================================
;; Layer 1 - Coordinated Operations
;; ============================================================================

(def ^:private default-store
  "Default in-memory store for heuristics."
  (atom nil))

(defn get-default-store
  "Get or create the default store."
  []
  (when (nil? @default-store)
    (reset! default-store (store/create-store :memory {})))
  @default-store)

(defn get-heuristic
  "Get a heuristic from the store."
  [heuristic-type version opts]
  (let [s (or (:store opts) (get-default-store))
        key (build-heuristic-key heuristic-type version)]
    (store/get-value s key)))

(defn save-heuristic
  "Save a heuristic to the store."
  [heuristic-type version data opts]
  (let [s (or (:store opts) (get-default-store))
        key (build-heuristic-key heuristic-type version)
        heuristic (assoc data
                         :heuristic/type heuristic-type
                         :heuristic/version version
                         :heuristic/saved-at (java.time.Instant/now))]
    (store/put-value s key heuristic)
    (java.util.UUID/randomUUID)))

(defn list-versions
  "List all versions of a heuristic type."
  [heuristic-type opts]
  (let [s (or (:store opts) (get-default-store))
        prefix (str (name heuristic-type) "/")
        all-keys (store/list-keys s)
        matching-keys (filter #(str/starts-with? % prefix) all-keys)
        versions (map #(subs % (count prefix)) matching-keys)]
    (sort-versions versions)))

(defn get-active-heuristic
  "Get the active version of a heuristic."
  [heuristic-type opts]
  (let [s (or (:store opts) (get-default-store))
        active-key (str (name heuristic-type) "/_active")
        active-version (store/get-value s active-key)]
    (when active-version
      (get-heuristic heuristic-type active-version opts))))

(defn set-active-version
  "Set the active version of a heuristic."
  [heuristic-type version opts]
  (let [s (or (:store opts) (get-default-store))
        active-key (str (name heuristic-type) "/_active")]
    (store/put-value s active-key version)
    true))

;; ============================================================================
;; Rich Comment
;; ============================================================================

(comment
  ;; Test version parsing and comparison
  (parse-version "1.2.3")
  ;; => {:major 1, :minor 2, :patch 3}

  (compare-versions "1.0.0" "1.1.0")
  ;; => -1

  (sort-versions ["1.0.0" "1.2.0" "1.1.0" "2.0.0"])
  ;; => ("2.0.0" "1.2.0" "1.1.0" "1.0.0")

  ;; Test heuristic operations
  (save-heuristic :implementer-prompt "1.0.0"
                  {:system "You are an implementer"
                   :task-template "Implement {{task}}"}
                  {})

  (get-heuristic :implementer-prompt "1.0.0" {})

  (list-versions :implementer-prompt {})

  (set-active-version :implementer-prompt "1.0.0" {})
  (get-active-heuristic :implementer-prompt {})

  :end)
