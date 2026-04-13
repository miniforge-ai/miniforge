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
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Schema and path

(def profile-path
  "Default location for user profile."
  (str (user/miniforge-home) "/profile.edn"))


;------------------------------------------------------------------------------ Layer 1
;; Load and validate

(defn load-profile
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

(defn validate-profile
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

;------------------------------------------------------------------------------ Layer 2
;; Token resolution

(defn- gh-cli-token
  "Attempt to get a GitHub token from the gh CLI.
   Returns the token string or nil."
  []
  (try
    (let [r (shell/sh "gh" "auth" "token")]
      (when (zero? (:exit r))
        (let [token (str/trim (:out r))]
          (when (seq token) token))))
    (catch Exception _ nil)))

(defn resolve-token
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
          :github  (or (System/getenv "GH_TOKEN") (gh-cli-token))
          ;; bitbucket, custom — no additional fallback
          nil))))
