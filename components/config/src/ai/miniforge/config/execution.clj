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
(ns ai.miniforge.config.execution
  "Execution settings in the user config: where agents may run.

   [:execution :require-governed?] true means every agent run must execute
   governed (in a container capsule), never :local (a host worktree with
   the operator's privileges). The CLI refuses such runs up front; the
   workflow runner refuses them again before it acquires the run's
   environment, which every run path reaches."
  (:require
   [ai.miniforge.config.user :as user]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} governed-required?
  "True when user config `cfg` requires governed execution. Fails closed:
   any value at [:execution :require-governed?] other than false or nil
   (\"true\", \"yes\" or 1 in a hand-edited file) counts as required, so a
   malformed value cannot turn the requirement off."
  [cfg]
  (let [v (get-in cfg [:execution :require-governed?])]
    (not (or (nil? v) (false? v)))))

(defn ^{:stratum 0} config-file-forms
  "Every top-level form in the EDN file at `path`, or ::unreadable when it
   cannot be read or parsed. nil when no file exists there. The end-of-file
   marker is a fresh object compared with `identical?`, so no form the file
   holds (a keyword spelt like a marker, say) can end the read early."
  [path]
  (when (.exists (io/file (str path)))
    (try+
     (let [eof (Object.)]
       (with-open [rdr (java.io.PushbackReader. (io/reader (str path)))]
         (loop [forms []]
           (let [form (edn/read {:eof eof} rdr)]
             (if (identical? eof form) forms (recur (conj forms form)))))))
     (catch Object _ ::unreadable))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} user-config-requires-governed?
  "Whether the user config file at `path` (default: the user config path)
   requires governed execution. Reads the file itself rather than through
   load-merged-config, which falls back to defaults when the file does not
   parse: the file must hold exactly one map, and anything else (a parse
   error, an empty file, nil, a vector, a second form after the map)
   counts as requiring governed execution, since it may be the file that
   sets the requirement. The one map is merged over the defaults as
   load-merged-config would. With no file, the defaults decide."
  ([] (user-config-requires-governed? user/default-user-config-path))
  ([path]
   (let [forms (config-file-forms path)]
     (cond
       (nil? forms)
       (governed-required? user/default-config)

       (and (vector? forms) (= 1 (count forms)) (map? (first forms)))
       (governed-required? (user/merge-configs user/default-config (first forms)))

       :else
       true))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (governed-required? {:execution {:require-governed? true}})  ; => true
  (governed-required? {:execution {:require-governed? "yes"}}) ; => true
  (governed-required? {})                                      ; => false

  :end)
