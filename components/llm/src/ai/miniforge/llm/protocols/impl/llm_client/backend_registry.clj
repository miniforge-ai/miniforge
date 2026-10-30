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
(ns ai.miniforge.llm.protocols.impl.llm-client.backend-registry
  "The backend registry (`backends`: the data in `llm/backends.edn`
   merged with each backend's stream parser and argument builder) and the
   network-health probe endpoint each backend resolves to."
  (:require
   [clojure.string :as str]
   [ai.miniforge.llm.protocols.impl.llm-client.cli-args :as cli-args]
   [ai.miniforge.llm.protocols.impl.llm-client.cli-output :as cli-output]
   [ai.miniforge.llm.protocols.impl.llm-client.settings :as settings]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

;; `:probe-endpoint` on each backend entry is the stable provider edge
;; URL that the network-monitor (PR-B) probes via
;; `network-health/probe-network`. We only care that the connection
;; completed, not the response status — a 4xx is the typical case (HEAD
;; against api.anthropic.com unauthenticated returns 405). Backends
;; without a provider-controlled endpoint (`:echo`) get the generic
;; Cloudflare DNS connectivity URL so the monitor still has something
;; to probe.
(def ^{:stratum 0} ^:private generic-connectivity-probe-url
  "https://1.1.1.1/")

(def ^{:stratum 0} ^:private probe-region-pattern
  "Shape of a cloud region name substituted into a route's
   `:regional-url`; anything else is ignored rather than spliced into
   a URL."
  #"[a-z0-9-]+")

(defn- ^{:stratum 0} http-url
  "`value`, trimmed, when it is an absolute http(s) URL with a host;
   nil otherwise."
  [value]
  (when (string? value)
    (try+
      (let [url (str/trim value)
            uri (java.net.URI. url)]
        (when (and (#{"http" "https"} (some-> (.getScheme uri) str/lower-case))
                   (.getHost uri))
          url))
      (catch java.net.URISyntaxException _
        nil))))

(defn- ^{:stratum 0} route-applies?
  "A route applies when it has no `:when-env` switch, or the switch is
   set and does not read as off (`CLAUDE_CODE_USE_BEDROCK=1`)."
  [{:keys [when-env]}]
  (or (nil? when-env)
      (let [value (settings/getenv-value when-env)]
        (and (not (str/blank? value))
             (not (settings/env-flag-off? value))))))

;; Function-valued backend fields. The :ollama entry is intentionally
;; absent (it has none); OpenCode loads credentials from its auth
;; store, environment, or project .env/config, so Miniforge should not
;; read provider-specific keys on that path.
(def ^{:stratum 0} ^:private backend-fns
  {:claude {:stream-parser cli-output/parse-claude-stream-line
            :args-fn cli-args/claude-args}
   :codex {:stream-parser cli-output/parse-codex-stream-line
           :args-fn cli-args/codex-args}
   :cursor {:args-fn cli-args/cursor-args}
   :opencode {:args-fn cli-args/opencode-args}
   :echo {:args-fn cli-args/echo-args}})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} route-endpoint
  "The probe URL a `:probe-endpoint-routes` entry yields from the
   environment: the URL in its `:base-url-env`, else its `:global-url`
   or `:regional-url` for the region in its `:region-env`. Nil when
   none of those is set (or usable)."
  [{:keys [base-url-env region-env regional-url global-url]}]
  (let [region (some-> region-env settings/getenv-value str/trim str/lower-case not-empty)]
    (or (some-> base-url-env settings/getenv-value http-url)
        (when (and global-url (= "global" region))
          global-url)
        (when (and regional-url region (re-matches probe-region-pattern region))
          (format regional-url region)))))

(def ^{:stratum 1} backends
  (merge-with merge settings/backend-data backend-fns))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} probe-endpoint-for
  "Resolve the network-health probe URL for `backend-key`: the endpoint
   the agent CLI actually talks to, read from the environment through
   the backend's `:probe-endpoint-routes` (`ANTHROPIC_BASE_URL`, the
   Bedrock and Vertex settings, `OPENAI_BASE_URL`). The first route that
   applies decides; when it yields no URL, or no route applies, the
   backend entry's static `:probe-endpoint` is used, and the generic
   Cloudflare DNS connectivity URL when there is none (unknown
   backends, entries without an endpoint).

   The caller (e.g. `stream-exec-fn` in PR-B) resolves the URL here and
   passes it to `network-health/probe-network` directly, keeping
   that namespace endpoint-agnostic and free of backend-config
   dependencies."
  [backend-key]
  (let [{:keys [probe-endpoint probe-endpoint-routes]} (get backends backend-key)
        route (first (filter route-applies? probe-endpoint-routes))]
    (or (some-> route route-endpoint)
        probe-endpoint
        generic-connectivity-probe-url)))
