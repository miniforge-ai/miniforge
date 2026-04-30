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

(ns ai.miniforge.dag-executor.protocols.impl.runtime.images
  "Loads the default OCI image references from
   `config/dag-executor/runtime/images.edn` and exposes lookups.

   Image references are configuration, not code — adding a new task-runner
   image (or pinning one to a digest) is an EDN edit. Fully-qualified
   references are required by N11-delta §5 so first-run on Podman never
   prompts for short-name resolution."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def images-resource-path
  "Classpath resource path for the image registry EDN."
  "config/dag-executor/runtime/images.edn")

(defn- read-edn-resource
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (-> resource slurp edn/read-string)))

(def images
  "Map of image-key -> {:image :dockerfile :description}, loaded once at
   namespace init."
  (delay (read-edn-resource images-resource-path)))

(defn entry
  "Return the image entry for `image-key`, or nil when not present."
  [image-key]
  (get @images image-key))

(defn task-runner-images
  "Return the map of buildable task-runner images (everything except
   :default). Excluding :default keeps the build-related helpers focused
   on images that ship with bundled Dockerfiles."
  []
  (dissoc @images :default))

(defn default-image
  "Fully-qualified default image reference used when an executor config
   omits :image."
  []
  (some-> (entry :default) :image))

(defn image
  "Fully-qualified image reference for `image-key`, or nil when unknown."
  [image-key]
  (some-> (entry image-key) :image))

(defn dockerfile
  "Classpath resource path to the Dockerfile for `image-key`, or nil when
   the image has no bundled Dockerfile."
  [image-key]
  (some-> (entry image-key) :dockerfile))
