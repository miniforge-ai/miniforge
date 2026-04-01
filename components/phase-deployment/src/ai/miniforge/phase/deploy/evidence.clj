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

(ns ai.miniforge.phase.deploy.evidence
  "Deployment-specific evidence types and collection.

   Every deployment run produces an immutable evidence bundle capturing enough
   state to reconstruct or audit the deployment without access to the original
   config store. This is the RFC's biggest operational weakness and Miniforge's
   strongest differentiation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

;------------------------------------------------------------------------------ Layer 0
;; Evidence types

(def evidence-types
  "Deployment evidence types with metadata."
  {:evidence/pulumi-preview
   {:description "Full pulumi preview --json output"
    :phase       :provision
    :captured    :after-preview
    :format      :json}

   :evidence/pulumi-outputs
   {:description "Stack outputs JSON (non-secret infrastructure values)"
    :phase       :provision
    :captured    :after-up
    :format      :json}

   :evidence/resolved-config
   {:description "Resolved non-secret config values from config store"
    :phase       :deploy
    :captured    :after-resolution
    :format      :edn}

   :evidence/rendered-manifests
   {:description "kustomize build output (actual K8s YAML applied)"
    :phase       :deploy
    :captured    :before-apply
    :format      :yaml}

   :evidence/image-digests
   {:description "Container image SHA256 digests for all deployed images"
    :phase       :deploy
    :captured    :before-apply
    :format      :edn}

   :evidence/policy-results
   {:description "Policy gate evaluation results (passed/blocked/warnings)"
    :phase       :provision
    :captured    :after-gate
    :format      :edn}

   :evidence/approvals
   {:description "Approval decisions and approver identity"
    :phase       :provision
    :captured    :after-approval
    :format      :edn}

   :evidence/environment-metadata
   {:description "Target environment: GCP project, GKE cluster, namespace, region"
    :phase       :all
    :captured    :workflow-start
    :format      :edn}

   :evidence/validation-results
   {:description "Health check and smoke test results"
    :phase       :validate
    :captured    :after-validation
    :format      :edn}

   :evidence/rollback-info
   {:description "Previous deployment revision and image tags"
    :phase       :deploy
    :captured    :before-deploy
    :format      :edn}})

;------------------------------------------------------------------------------ Layer 1
;; Hashing + evidence item creation

(defn sha256
  "Compute SHA-256 hash of content string.
   Returns hex-encoded hash string."
  [content]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes (str content) "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

;; Evidence item creation

(defn create-evidence
  "Create an evidence item with content hash and timestamp.

   Arguments:
     evidence-type - Keyword from evidence-types (e.g. :evidence/pulumi-preview)
     content       - Evidence content (string, map, or collection)
     metadata      - Optional extra metadata map

   Returns:
     {:evidence/type      keyword
      :evidence/content   any
      :evidence/hash      string (sha256)
      :evidence/timestamp long (epoch ms)
      :evidence/metadata  map}"
  [evidence-type content & [metadata]]
  (let [content-str (if (string? content) content (pr-str content))]
    (cond-> {:evidence/type      evidence-type
             :evidence/content   content
             :evidence/hash      (str "sha256:" (sha256 content-str))
             :evidence/timestamp (System/currentTimeMillis)}
      metadata (assoc :evidence/metadata metadata))))

(defn create-environment-metadata
  "Create environment metadata evidence for the start of a deployment run.

   Arguments:
     config - Deployment config map with :gcp-project, :gke-cluster,
              :namespace, :region, etc."
  [config]
  (create-evidence
   :evidence/environment-metadata
   {:gcp-project  (:gcp-project config)
    :gke-cluster  (:gke-cluster config)
    :namespace    (:namespace config)
    :region       (:region config)
    :stack        (:stack config)
    :timestamp    (System/currentTimeMillis)}))

;------------------------------------------------------------------------------ Layer 2
;; Bundle assembly + persistence

(defn create-deployment-bundle
  "Assemble a complete deployment evidence bundle.

   Arguments:
     workflow-run-id - UUID of the workflow run
     evidence-items  - Vector of evidence items (from create-evidence)

   Returns:
     {:bundle/id          uuid
      :bundle/workflow-id  uuid
      :bundle/evidence    vector of evidence items
      :bundle/created-at  long (epoch ms)
      :bundle/complete?   boolean (all required types present)}"
  [workflow-run-id evidence-items]
  (let [present-types (set (map :evidence/type evidence-items))
        required-types #{:evidence/environment-metadata
                         :evidence/rendered-manifests
                         :evidence/image-digests}]
    {:bundle/id          (random-uuid)
     :bundle/workflow-id workflow-run-id
     :bundle/evidence    (vec evidence-items)
     :bundle/created-at  (System/currentTimeMillis)
     :bundle/complete?   (every? present-types required-types)}))

;; Evidence persistence (filesystem-based, content-addressed)

(defn persist-evidence!
  "Persist an evidence item to the filesystem.

   Arguments:
     base-dir  - Root directory for evidence storage
     run-id    - Workflow run ID (used as subdirectory)
     evidence  - Evidence item from create-evidence

   Returns:
     Evidence item with :evidence/path added."
  [base-dir run-id evidence]
  (let [hash-str   (str/replace (:evidence/hash evidence) "sha256:" "")
        type-name  (name (:evidence/type evidence))
        ext        (case (get-in evidence-types [(:evidence/type evidence) :format])
                     :json ".json"
                     :yaml ".yaml"
                     :edn  ".edn"
                     ".dat")
        file-name  (str type-name "-" (subs hash-str 0 12) ext)
        dir        (io/file base-dir (str run-id) "evidence")
        file       (io/file dir file-name)
        content    (:evidence/content evidence)
        content-str (if (string? content) content (pr-str content))]
    (.mkdirs dir)
    (spit file content-str)
    (assoc evidence :evidence/path (.getAbsolutePath file))))

(defn persist-bundle!
  "Persist a complete evidence bundle (manifest + all items).

   Arguments:
     base-dir - Root directory for evidence storage
     bundle   - Bundle from create-deployment-bundle

   Returns:
     Bundle with :bundle/path and all evidence items updated with :evidence/path."
  [base-dir bundle]
  (let [run-id    (str (:bundle/workflow-id bundle))
        persisted (mapv #(persist-evidence! base-dir run-id %) (:bundle/evidence bundle))
        bundle    (assoc bundle :bundle/evidence persisted)
        dir       (io/file base-dir run-id)
        manifest  (io/file dir "bundle.edn")]
    (.mkdirs dir)
    (spit manifest (pr-str (dissoc bundle :bundle/evidence)))
    ;; Also write a manifest with evidence paths (not content) for quick inspection
    (spit (io/file dir "manifest.edn")
          (pr-str {:bundle/id         (:bundle/id bundle)
                   :bundle/workflow-id (:bundle/workflow-id bundle)
                   :bundle/created-at  (:bundle/created-at bundle)
                   :bundle/complete?   (:bundle/complete? bundle)
                   :evidence           (mapv #(select-keys % [:evidence/type
                                                              :evidence/hash
                                                              :evidence/path
                                                              :evidence/timestamp])
                                             persisted)}))
    (assoc bundle :bundle/path (.getAbsolutePath dir))))

;; Evidence extraction helpers (for adding to phase context)

(defn extract-image-digests
  "Extract container image SHA256 digests from rendered K8s manifests.

   Arguments:
     rendered-yaml - String of rendered Kubernetes YAML manifests

   Returns:
     Vector of {:image string :digest string-or-nil}"
  [rendered-yaml]
  (let [image-pattern #"image:\s*[\"']?([^\s\"']+)[\"']?"
        images (->> (re-seq image-pattern rendered-yaml)
                    (map second)
                    distinct)]
    (mapv (fn [img]
            (let [[repo digest] (str/split img #"@" 2)]
              {:image img
               :repository repo
               :digest digest}))
          images)))

(defn add-evidence-to-ctx
  "Add an evidence item to the execution context's evidence collection.

   Arguments:
     ctx      - Phase execution context
     evidence - Evidence item from create-evidence

   Returns:
     Updated context."
  [ctx evidence]
  (update ctx :execution/evidence (fnil conj []) evidence))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create evidence items
  (create-evidence :evidence/pulumi-preview "{\"steps\":[]}" {:stack "dev"})
  (create-evidence :evidence/rendered-manifests "apiVersion: v1\nkind: Pod\n...")

  ;; Persist
  (persist-evidence! "./deployments" (random-uuid)
                     (create-evidence :evidence/pulumi-preview "{}"))

  ;; Image digest extraction
  (extract-image-digests "image: gcr.io/myproj/myapp@sha256:abc123\nimage: nginx:latest")

  :leave-this-here)
