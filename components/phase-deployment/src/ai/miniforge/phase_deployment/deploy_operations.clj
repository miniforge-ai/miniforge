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
(ns ai.miniforge.phase-deployment.deploy-operations
  "The real provider, shaped for the governed transaction.

   `deploy-governed/transact!` takes an injected operations map so its
   tests need no cluster. This builds that map from the actual provider
   functions.

   THE PROPERTY THIS PRESERVES. `deploy-provider` renders manifests,
   server-dry-runs exact bytes, and applies exact bytes — three separate
   operations over one artifact. Naively adapting them would render
   once for the dry-run and again for the apply, which quietly reopens
   the gap the whole seam exists to close: you would validate one thing
   and apply another. So the rendered bytes are captured once and both
   the dry-run and the apply use that same value. The record then
   describes what actually went to the cluster."
  (:require
   [ai.miniforge.phase-deployment.deploy-provider :as provider]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} rendered-or-nil
  "The manifest text from a render result, or nil when it did not
   render. `transact!` treats a blank render as a denial — we cannot
   show an apply is safe without seeing what it does."
  [result]
  (when-not (schema/failed? result)
    (or (:rendered-yaml result)
        (get-in result [:build-result :stdout]))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} operations
  "Provider operations bound to ONE rendered artifact.

   The atom is not incidental state — it is what makes 'dry-run and
   apply saw the same bytes' true rather than hoped for. `:dry-run!`
   renders, asks the API server to validate those exact bytes, and
   remembers them; `:apply!` sends the remembered ones."
  []
  (let [rendered (atom nil)]
    {:dry-run!
     (fn [deploy-config]
       (let [text (rendered-or-nil (provider/render! deploy-config))]
         (reset! rendered text)
         (when text
           (let [validation (provider/dry-run! deploy-config text)]
             ;; A render that the API server rejects is not a usable
             ;; preflight. Returning nil denies it rather than letting a
             ;; rejected manifest through as though it had passed.
             (when-not (schema/failed? validation) text)))))

     :apply!
     (fn [deploy-config]
       (let [text @rendered
             result (if (nil? text)
                      {:error "no rendered manifest to apply"}
                      (provider/apply-rendered! deploy-config text))]
         {:deploy/failed? (or (nil? text) (schema/failed? result))
          :deploy/failure (:error result)
          :deploy/rollback-info nil}))

     :observe! (fn [deploy-config] (provider/observe! deploy-config))
     :rollback-info! (fn [deploy-config] (provider/rollback-info! deploy-config))}))
