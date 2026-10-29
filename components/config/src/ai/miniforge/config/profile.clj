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
(ns ai.miniforge.config.profile
  "User profile management (~/.miniforge/profile.edn).

   Provides token storage, identity, and default preferences using Aero
   for environment variable resolution. Profile is user-scoped — never
   checked into a repository.

   Token resolution order:
   1. MINIFORGE_GIT_TOKEN env (universal override)
   2. Profile token for the requested host kind
   3. Provider-specific env var (GH_TOKEN, GITLAB_TOKEN)
   4. CLI fallback (gh auth token)

   Layer 0: Schema and path
   Layer 1: Load and validate
   Layer 2: Token resolution"
  (:require
   [aero.core :as aero]
   [ai.miniforge.config.user :as user]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Schema and path
(def ^{:stratum 0} profile-path
  (str (user/miniforge-home) "/profile.edn"))

(defn ^{:stratum 0} validate-profile
  "Validate a profile map. Returns {:valid? bool :errors [...]}.
   Checks that :tokens is a map with keyword keys and string values,
   and that :identity has :name and :email when present."
  [profile]
  (let [errors (cond-> []
                 (and (:tokens profile)
                      (not (map? (:tokens profile))))
                 (conj ":tokens must be a map")

                 (and (:identity profile)
                      (not (map? (:identity profile))))
                 (conj ":identity must be a map")

                 (and (get-in profile [:identity :email])
                      (not (string? (get-in profile [:identity :email]))))
                 (conj ":identity/:email must be a string")

                 (and (get-in profile [:identity :name])
                      (not (string? (get-in profile [:identity :name]))))
                 (conj ":identity/:name must be a string"))]
    {:valid? (empty? errors)
     :errors errors}))

;; Token resolution
(def ^{:stratum 0} ^:private gh-token-timeout-ms
  "Ceiling on `gh auth token`. A gh that stalls (a keyring prompt, a hung
   credential store) must not block every token lookup behind it."
  10000)

(defn- ^{:stratum 0} destroy-process-tree!
  "Kill `p` and every process it started, leaf first, as the llm and
   dag-executor `destroy-process-tree!` helpers do; config sits below both
   components, so it cannot call them. Descendants are found while `p` is
   alive: a child that outlives `p` is reparented and out of reach."
  [^Process p]
  (doseq [^java.lang.ProcessHandle child (rseq (vec (.toArray (.descendants p))))]
    (.destroyForcibly child))
  (.destroyForcibly p))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} gh-cli-token
  "Attempt to get a GitHub token from the gh CLI (`cmd`, by default
   `gh auth token`). Returns the token; nil when gh gives none; :timeout
   when gh has not printed it and exited within `timeout-ms`; or
   :interrupted when the calling thread is interrupted, with its interrupt
   flag set again. A reader thread takes gh's first output line under the
   same deadline, so a child that keeps stdout open after gh exits cannot
   hold the read. However it ends, gh and every process it started (a
   credential helper, or the real gh behind a wrapper) are killed."
  ([] (gh-cli-token ["gh" "auth" "token"] gh-token-timeout-ms))
  ([cmd timeout-ms]
   (try
     (let [p        (.start (doto (ProcessBuilder. ^java.util.List cmd)
                              (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)))
           deadline (+ (System/nanoTime) (* timeout-ms 1000000))
           line     (future (.readLine (io/reader (.getInputStream p))))]
       (try
         (let [token (deref line timeout-ms ::timeout)]
           (cond
             (= ::timeout token) :timeout
             (not (.waitFor p (- deadline (System/nanoTime))
                            java.util.concurrent.TimeUnit/NANOSECONDS)) :timeout
             (zero? (.exitValue p)) (some-> token str/trim not-empty)))
         (catch InterruptedException _
           (.interrupt (Thread/currentThread))
           :interrupted)
         (finally
           (destroy-process-tree! p)
           (future-cancel line))))
     (catch Exception _ nil))))

;; Load and validate
(defn ^{:stratum 1} load-profile
  "Load user profile from ~/.miniforge/profile.edn using Aero.
   Returns the profile map, or nil if file does not exist."
  ([] (load-profile profile-path))
  ([path]
   (let [f (io/file path)]
     (when (.exists f)
       (try
         (aero/read-config f)
         (catch Exception _
           nil))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resolve-token
  "Resolve a git authentication token for a given host kind.

   Resolution order:
   1. MINIFORGE_GIT_TOKEN env var (universal override)
   2. Profile :tokens entry for the host kind
   3. Provider-specific env var (GH_TOKEN / GITLAB_TOKEN)
   4. gh CLI fallback (GitHub only)

   Arguments:
   - host-kind: :github, :gitlab, :bitbucket, or :custom
   - opts: optional map with :profile (pre-loaded profile map)

   Returns: token string or nil."
  [host-kind & [{:keys [profile]}]]
  (let [profile (or profile (load-profile))]
    (or (System/getenv "MINIFORGE_GIT_TOKEN")
        (get-in profile [:tokens host-kind])
        (case host-kind
          :gitlab  (System/getenv "GITLAB_TOKEN")
          ;; :timeout and :interrupted are no token; an interrupt stays
          ;; set on the thread, so the caller's next wait stops too.
          :github  (or (System/getenv "GH_TOKEN") (let [t (gh-cli-token)] (when (string? t) t)))
          ;; bitbucket, custom — no additional fallback
          nil))))
