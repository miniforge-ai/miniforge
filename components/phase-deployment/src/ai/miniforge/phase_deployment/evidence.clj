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

(ns ai.miniforge.phase-deployment.evidence
  "Deployment-specific evidence types and collection."
  (:require [ai.miniforge.schema.interface :as schema]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas + config loading

(def EvidenceTypeMetadata
  [:map
   [:description :string]
   [:phase keyword?]
   [:captured keyword?]
   [:format [:enum :json :yaml :edn]]])

(def EvidenceConfig
  [:map
   [:deploy/evidence-types [:map-of keyword? EvidenceTypeMetadata]]])

(def EvidenceItem
  [:map
   [:evidence/type keyword?]
   [:evidence/content any?]
   [:evidence/hash :string]
   [:evidence/timestamp integer?]
   [:evidence/metadata {:optional true} map?]
   [:evidence/path {:optional true} :string]])

(def EnvironmentMetadata
  [:map
   [:gcp-project {:optional true} [:maybe :string]]
   [:gke-cluster {:optional true} [:maybe :string]]
   [:namespace {:optional true} [:maybe :string]]
   [:region {:optional true} [:maybe :string]]
   [:stack {:optional true} [:maybe :string]]
   [:timestamp integer?]])

(def DeploymentBundle
  [:map
   [:bundle/id uuid?]
   [:bundle/workflow-id uuid?]
   [:bundle/evidence [:vector EvidenceItem]]
   [:bundle/created-at integer?]
   [:bundle/complete? :boolean]
   [:bundle/path {:optional true} :string]])

(defn- validate!
  [result-schema value]
  (schema/validate result-schema value))

(defn- load-evidence-config
  []
  (->> "config/phase/deploy-evidence.edn"
       io/resource
       slurp
       edn/read-string
       (validate! EvidenceConfig)))

(def evidence-types
  "Deployment evidence types loaded from EDN configuration."
  (:deploy/evidence-types (load-evidence-config)))

(defn- evidence-format
  [evidence-type]
  (get-in evidence-types [evidence-type :format]))

(defn- persisted-bundle-manifest
  [bundle]
  {:bundle/id          (:bundle/id bundle)
   :bundle/workflow-id (:bundle/workflow-id bundle)
   :bundle/created-at  (:bundle/created-at bundle)
   :bundle/complete?   (:bundle/complete? bundle)
   :evidence           (mapv #(select-keys % [:evidence/type
                                              :evidence/hash
                                              :evidence/path
                                              :evidence/timestamp])
                             (:bundle/evidence bundle))})

;------------------------------------------------------------------------------ Layer 1
;; Hashing + evidence creation

(defn sha256
  "Compute SHA-256 hash of content string."
  [content]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes (str content) "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(defn create-evidence
  "Create a validated evidence item."
  [evidence-type content & [metadata]]
  (let [content-str (if (string? content) content (pr-str content))]
    (validate!
     EvidenceItem
     (cond-> {:evidence/type      evidence-type
              :evidence/content   content
              :evidence/hash      (str "sha256:" (sha256 content-str))
              :evidence/timestamp (System/currentTimeMillis)}
       metadata (assoc :evidence/metadata metadata)))))

(defn create-environment-metadata
  "Create environment metadata evidence for the start of a deployment run."
  [config]
  (create-evidence
   :evidence/environment-metadata
   (validate! EnvironmentMetadata
              {:gcp-project (get config :gcp-project)
               :gke-cluster (get config :gke-cluster)
               :namespace   (get config :namespace)
               :region      (get config :region)
               :stack       (get config :stack)
               :timestamp   (System/currentTimeMillis)})))

(defn create-deployment-bundle
  "Assemble a complete deployment evidence bundle."
  [workflow-run-id evidence-items]
  (let [present-types  (set (map :evidence/type evidence-items))
        required-types #{:evidence/environment-metadata
                         :evidence/rendered-manifests
                         :evidence/image-digests}]
    (validate!
     DeploymentBundle
     {:bundle/id          (random-uuid)
      :bundle/workflow-id workflow-run-id
      :bundle/evidence    (vec evidence-items)
      :bundle/created-at  (System/currentTimeMillis)
      :bundle/complete?   (every? present-types required-types)})))

;------------------------------------------------------------------------------ Layer 2
;; Persistence + extraction helpers

(defn persist-evidence!
  "Persist an evidence item to the filesystem."
  [base-dir run-id evidence]
  (let [hash-str    (str/replace (:evidence/hash evidence) "sha256:" "")
        type-name   (name (:evidence/type evidence))
        ext         (case (evidence-format (:evidence/type evidence))
                      :json ".json"
                      :yaml ".yaml"
                      :edn ".edn"
                      ".dat")
        file-name   (str type-name "-" (subs hash-str 0 12) ext)
        dir         (io/file base-dir (str run-id) "evidence")
        file        (io/file dir file-name)
        content     (:evidence/content evidence)
        content-str (if (string? content) content (pr-str content))]
    (.mkdirs dir)
    (spit file content-str)
    (validate! EvidenceItem
               (assoc evidence :evidence/path (.getAbsolutePath file)))))

(defn persist-bundle!
  "Persist a complete evidence bundle (full bundle + manifest + all items)."
  [base-dir bundle]
  (let [run-id     (str (:bundle/workflow-id bundle))
        persisted  (mapv #(persist-evidence! base-dir run-id %) (:bundle/evidence bundle))
        bundle*    (validate! DeploymentBundle
                              (assoc bundle :bundle/evidence persisted))
        dir        (io/file base-dir run-id)
        bundle-edn (io/file dir "bundle.edn")
        manifest   (io/file dir "manifest.edn")]
    (.mkdirs dir)
    ;; Persist the full bundle, including curated evidence metadata, hashes, paths,
    ;; and the original evidence content for full reconstruction.
    (spit bundle-edn (pr-str bundle*))
    ;; Persist the lightweight manifest separately for quick inspection.
    (spit manifest (pr-str (persisted-bundle-manifest bundle*)))
    (validate! DeploymentBundle
               (assoc bundle* :bundle/path (.getAbsolutePath dir)))))

(defn extract-image-digests
  "Extract container image digests from rendered K8s manifests."
  [rendered-yaml]
  (let [image-pattern #"image:\s*[\"']?([^\s\"']+)[\"']?"
        images        (->> (re-seq image-pattern rendered-yaml)
                           (map second)
                           distinct)]
    (mapv (fn [img]
            (let [[repo digest] (str/split img #"@" 2)]
              {:image img
               :repository repo
               :digest digest}))
          images)))

(defn add-evidence-to-ctx
  "Add an evidence item to the execution context."
  [ctx evidence]
  (update ctx :execution/evidence (fnil conj []) evidence))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (create-evidence :evidence/pulumi-preview "{\"steps\":[]}" {:stack "dev"})
  (persist-evidence! "./deployments" (random-uuid)
                     (create-evidence :evidence/pulumi-preview "{}"))
  (extract-image-digests "image: gcr.io/myproj/myapp@sha256:abc123\nimage: nginx:latest")
  :leave-this-here)
