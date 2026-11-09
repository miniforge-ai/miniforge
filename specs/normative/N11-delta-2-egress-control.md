<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N11 Delta 2 — Capsule Egress Control

**Version:** 0.1.0-draft
**Date:** 2026-11-08
**Status:** Draft
**Conformance:** MUST

- **Spec ID:** `N11-delta-egress-control-v1`
- **Amends:** N11 — Task Capsule Isolation (§2.2 Network and Credentials, §5.1, §5.3, §8.1, §8.2)
- **Amends:** N11-delta-runtime-adapter (§4 capability set)
- **Related:** N3 (event stream), N4 (policy packs), N6 (evidence), N12 (agent context)
- **Source:** operator decision of 2026-11-08 to adopt the egress design of PAODO-Workspaces
  (MIT, `github.com/paodo-solution/PAODO-Workspaces` at `1b35459`): its credential-proxy, domain-allowlist,
  and internet-access ADRs.

---

## 0. Architectural Decision Record

> **A governed capsule that has network access SHALL reach the network only through a per-capsule egress
> proxy. The proxy enforces an exact-host allowlist and a destination-address guard, and it holds the agent's
> credentials. The capsule holds opaque tokens in their place.**

N11 §2.2 requires deny-by-default networking and ephemeral credentials. The OCI-CLI executor offers two states:
no network, or the runtime's default network with unrestricted egress (Annex A). An agent on that network can reach
any host, including link-local metadata endpoints and the host's private network, and it can read its own model
API key from its environment. A prompt injection can therefore send the key, or anything else in the workspace,
to any host.

The boundary is the network, not an environment variable. The capsule joins an internal network whose only
other member is the proxy, so a tool that ignores `HTTPS_PROXY` has no route out.

### 0.1 Non-Goals

- This delta does NOT restrict what an allowed host receives. An allowed host can still receive workspace data,
  and a host that serves anyone's content can still deliver injected text. Paths are not checked.
- This delta does NOT defend against a capsule escape through the shared host kernel.
- This delta does NOT add a Docker Engine or Podman HTTP API client (N11-delta §1.1).
- This delta does NOT change local mode (N11 §7.1).

---

## 1. Network Profiles

`:network-profile` (execution plan) takes one of four values. N11 §5.1's `:network-policy` maps onto it (§11).

| Profile | Capsule network | Proxy | Reachable hosts |
|---------|-----------------|-------|-----------------|
| `:none` | `--network=none` | none | none |
| `:restricted` | internal network | yes | the derived and operator-declared hosts of §3.1 |
| `:standard` | internal network | yes | `:restricted` hosts plus the baseline list of §3.1 |
| `:full` | runtime default network | none | any |

1. In governed mode the default profile MUST be `:standard`.
2. In governed mode `:full` MUST be refused with `:egress/profile-forbidden`. N11 §2.2 requires undeclared
   network access to be blocked, and `:full` blocks nothing.
3. `:none` keeps `--network=none`. No proxy or internal network is created. An agent inside a `:none` capsule
   cannot reach its model API, so `:none` suits capsules that run no agent.
4. A capsule under `:restricted` or `:standard` MUST NOT be attached to any network other than its internal
   network.

---

## 2. Egress Topology

### 2.1 Components

Each governed capsule under `:restricted` or `:standard` has:

1. **An internal network** created for that capsule alone, with no gateway route. Its only members are the
   capsule and its proxy.
2. **An egress proxy sidecar**: a container from a pinned, fully qualified proxy image (N11-delta §5). It joins
   the internal network and one network with outbound access. It runs with the hardening flags the capsule
   gets (`--cap-drop=ALL`, `--read-only`, `no-new-privileges`, non-root user, memory and CPU limits).
3. **Proxy routing in the capsule env**: `HTTPS_PROXY`, `HTTP_PROXY` and their lowercase forms name the
   sidecar on the internal network. `NO_PROXY` names loopback only.
4. **No outside name resolution.** The capsule MUST NOT be able to resolve names beyond its internal network.
   The proxy resolves upstream names. A resolver that forwards the capsule's queries outward is an exfiltration
   channel: data encoded in query names reaches whoever runs the queried domain's nameserver.

One sidecar serves one capsule, so the source of every connection identifies the capsule. The proxy needs no
per-connection identity scheme.

The sidecar MUST NOT run inside the miniforge host process. A host-side proxy would put a route to the control
plane on every capsule's network.

### 2.2 Sidecar Isolation

1. The sidecar MUST inherit no environment from the miniforge process beyond what this delta names.
2. Credential values (§5) and the allowlist reach the sidecar at its launch through an owner-only env file or a
   tmpfs file, never on argv.
3. The capsule MUST NOT be able to exec into, signal, or read the filesystem of the sidecar.
4. The sidecar MUST NOT accept connections on its outbound network.

### 2.3 Lifecycle

`acquire-environment!` MUST perform these steps in order:

1. Create the internal network.
2. Start the sidecar. Wait until it reports ready and its CA certificate (§6) can be read.
3. Read the CA certificate from the sidecar.
4. Create the capsule on the internal network, with proxy routing (§2.1) and CA trust (§6) in its env.
5. Bootstrap the workspace (N11 §4.2). The clone goes through the proxy.

The capsule MUST NOT start before step 2 completes. Before then it would have either no proxy or a route the
proxy does not control.

A failure at any step MUST fail the acquisition and remove what earlier steps created (N11.MD.1). It MUST NOT
retry the launch under a less restrictive profile.

`release-environment!` MUST remove the capsule, the sidecar, and the internal network. It MUST succeed when any
of them is already gone (N11 §10.2). Resource names carry the task-id slice the capsule name carries, so
release can find each one.

---

## 3. Allowlist

### 3.1 Sources

The allowlist of a capsule is the union of:

1. **Derived hosts.** The hosts of each credential the launch carries (§5.2), the host of the endpoint the
   agent backend calls (the route `probe-endpoint-for` resolves), and the host of the repository's git remote.
2. **Operator hosts.** Entries under `:miniforge/egress :allow` in user or repository configuration, and the
   `:network-policy :allow` entries of an operator-authored runtime specification (N11 §5.1).
3. **Baseline hosts**, under `:standard` only. A list in an EDN resource of the dag-executor component, with a
   Malli schema, covering package registries in common use. It is configuration, not code.

Agent-produced artifacts (plans, generated DAG task definitions, phase outputs) MUST NOT add allowlist entries.
A prompt-injected plan could otherwise open its own exfiltration route.

### 3.2 Matching

1. An entry is a hostname, matched exactly and case-insensitively after normalization (lowercase, trailing dot
   removed). `example.com` matches neither `www.example.com` nor `api.example.com`.
2. No wildcards. A redirect target and each subdomain need their own entry.
3. An entry MAY name a port. The default port is 443. A CONNECT to an unlisted port MUST be refused.
4. A destination given as an IP literal MUST be refused under `:restricted` and `:standard`, since entries name
   hosts.
5. An allowlist with no entries MUST refuse every host. An empty list never means unrestricted.
6. An entry that fails to parse MUST reject the whole configuration, and the acquisition fails.

### 3.3 Enforcement

The proxy MUST check the requested host against the allowlist before it opens any upstream connection, for both
`CONNECT` and plain HTTP requests. A refused request gets HTTP `403` (§7.1). No upstream sees it.

A credential's hosts (§5.2) are not exempt from the list: they enter it as derived hosts, so the recorded
allowlist (§9) names every host the capsule can reach.

---

## 4. Destination Guard

The proxy MUST refuse a connection whose dialed address is not public, under every profile that has a proxy.

1. The check applies to the address the proxy actually connects to, resolved and checked in one step, so a DNS
   answer that changes between check and connect cannot pass.
2. Blocked IPv4 ranges: `0.0.0.0/8`, `10.0.0.0/8`, `100.64.0.0/10`, `127.0.0.0/8`, `169.254.0.0/16`,
   `172.16.0.0/12`, `192.0.0.0/24`, `192.0.2.0/24`, `192.168.0.0/16`, `198.18.0.0/15`, `198.51.100.0/24`,
   `203.0.113.0/24`, and `224.0.0.0/3`.
3. Blocked IPv6 ranges: `::`, `::1`, `fc00::/7`, `fe80::/10`, `ff00::/8`.
4. An IPv6 address that embeds an IPv4 address (`::ffff:0:0/96` in dotted or hex form, `::/96`, and
   `64:ff9b::/96`) MUST be checked as its embedded IPv4 address. Without this, `::ffff:a9fe:a9fe` reaches
   `169.254.169.254`.
5. Input that does not parse as an address MUST be refused.
6. When any address a name resolves to is blocked, the connection MUST be refused.

The ranges are data in the same EDN resource as §3.1's baseline. A private destination the operator needs (an
internal registry) MAY be listed under `:miniforge/egress :private-allow` as a CIDR in operator configuration.
That list follows §3.1's rule: agent-produced artifacts cannot extend it.

---

## 5. Credential Substitution

### 5.1 Tokens in the Capsule

For each agent credential the launch carries (`agent-credential-names` and the agent backend's
`llm/backend-credential-env`), the capsule env MUST carry an
opaque token under the credential's variable name in place of its value. The value MUST exist only in the
miniforge host process and the sidecar.

1. A token MUST hold at least 128 bits from a CSPRNG and MUST be unique to the capsule.
2. A token MUST keep the credential's public prefix (for example `sk-ant-api03-`) when the agent CLI checks the
   format locally. The prefix is not secret.
3. A token MUST NOT be derivable from the value, and the value MUST NOT be derivable from the token.

### 5.2 Credential Hosts

Each credential is scoped to the exact hosts declared for it as data in `backends.edn` (for `:claude`,
`api.anthropic.com`). When the backend's endpoint route resolves to another host (a base-URL override in
operator configuration), that host replaces the declared one. Subdomains are excluded. The proxy MUST substitute
a token only on a connection to one of that credential's hosts.

### 5.3 Interception

1. The proxy MUST terminate TLS only for a host that some credential is scoped to. Every other allowed host is a
   plain tunnel, with no interception.
2. On an intercepted connection, the proxy MUST replace tokens with values in the request target and every
   header, including inside a `Basic` authorization envelope. It MAY substitute in request bodies up to a
   configured size bound.
3. The proxy MUST re-encrypt to the upstream with full certificate verification against the sidecar's system
   roots.
4. Plain HTTP MUST NOT be substituted. The token travels as-is, and the upstream rejects it.

### 5.4 Redaction

On an intercepted connection the proxy MUST replace each credential value, and each `Basic` envelope it built,
with the token, in response headers and bodies. Bodies MUST stream: the proxy holds back at most the length of
the longest value minus one byte, so a value split across chunks is caught and server-sent events are not
buffered. The proxy SHOULD remove `Accept-Encoding` from intercepted requests, so responses stay readable for
redaction.

### 5.5 Credentials the Proxy Cannot Substitute

Signed requests (AWS SigV4 for Bedrock, Google service-account signing for Vertex) carry no token to swap.

1. Such a credential MUST NOT be injected into a governed capsule unless the operator lists its variable name
   under `:miniforge/egress :in-capsule-credentials`. Otherwise the acquisition fails with
   `:egress/credential-not-substitutable`.
2. A credential injected under that list MUST be recorded by name in evidence (N11 §8.1 permits names).

### 5.6 OAuth

`CLAUDE_CODE_OAUTH_TOKEN` is a static bearer credential sent to the backend's API host. It MUST be handled like
an API key under §5.1–§5.4.

A credential that refreshes itself MUST NOT enter a governed capsule. A credential file with a refresh token
(an interactive CLI login) would receive a new access token in a response body. The proxy does not know that
value, so it cannot redact it, and the capsule would hold a real credential. No launch path may deliver such a
credential, by mount or by env.

### 5.7 Git Remote Credentials

The git remote credential the executor passes to each capsule git command SHOULD be handled under §5.1–§5.4,
scoped to the remote's host. Until it is, it travels through a plain tunnel and remains readable inside the
capsule while the command runs.

---

## 6. Proxy CA Trust

### 6.1 Per-Capsule CA

1. The sidecar MUST generate its CA at start. The CA private key MUST NOT leave the sidecar.
2. The CA MUST be destroyed with the sidecar at DESTROY. A CA MUST NOT be shared across capsules.
3. Leaf certificates MUST be issued only for credential hosts (§5.2).

### 6.2 Trust in the Capsule

At step 4 of §2.3 the executor writes a combined bundle (the image's system roots plus the proxy CA) to a tmpfs
path in the capsule and sets:

| Variable | Read by |
|----------|---------|
| `NODE_EXTRA_CA_CERTS` | Node CLIs, including Claude Code; adds the proxy CA to the built-in roots |
| `SSL_CERT_FILE` | OpenSSL and rustls-based clients that read it |
| `CURL_CA_BUNDLE` | curl |
| `GIT_SSL_CAINFO` | git |
| `REQUESTS_CA_BUNDLE` | Python `requests` |

`NODE_EXTRA_CA_CERTS` names the proxy CA alone. The others replace the default store, so they name the combined
bundle, or tunneled hosts would fail to verify.

### 6.3 Per-Backend Trust

Each CLI backend MUST declare in `backends.edn` the variables through which it trusts an added CA. A backend with
no such declaration MUST NOT run in a capsule whose credentials are substituted.

The Codex CLI's trust mechanism is not settled. Before Codex runs under this delta, a conformance test MUST show
it completing one call through the intercepting proxy with only §6.2's variables set (Test Obligation 6).

---

## 7. Refusals

### 7.1 Proxy Response

A refused request gets HTTP `403` with header `X-Miniforge-Egress-Refused` set to `host-not-allowed`,
`port-not-allowed`, `ip-literal`, or `destination-blocked`. The plain-text body names the refused host and the
reason in one sentence. The proxy MUST record each refusal for the run (§9).

### 7.2 Agent Context

The agent's task context MUST state, for a capsule with network access:

1. the profile, and the allowed hosts;
2. that each subdomain and redirect target needs its own entry;
3. that a refusal is final: the agent names the refused host in its output as a blocker for the operator and
   does not retry the request.

Under `:none` it MUST state that the capsule has no network. The statement belongs to the system prompt, which
N12 §5.4 treats as irreducible, so context shedding never removes it. Its text is a message-catalog entry.

### 7.3 Operator Surface

`mf doctor` SHOULD report the resolved profile and allowlist for the next governed run. A run's refused hosts
SHOULD be shown in its summary, so the operator can add a missing host instead of reading logs.

---

## 8. Runtime Registry Mapping

Code SHALL NOT branch on `:runtime/kind` (N11-delta §2) and SHALL NOT call a runtime daemon API. Every
difference between runtimes lives in `runtime/registry.edn`.

### 8.1 Capabilities

This delta adds two capabilities to N11-delta §4:

| Capability | Meaning |
|------------|---------|
| `:network-modes/internal` | Creates and removes a user network with no outbound route (`network create --internal`) |
| `:network-attach` | Attaches a container to more than one network |

`:restricted` and `:standard` require both. The executor MUST check them before step 1 of §2.3. If either is
missing, the acquisition fails with `:egress/capability-missing`, naming the capability.

### 8.2 Flag Dialect

The CLI argument vectors for creating, attaching, and removing a network are `:flags` entries keyed by
capability. A runtime whose syntax differs (for example, one that takes several `--network` flags at `run`
while another needs a separate `network connect`) declares its own vector. The executor composes argv from the
entry and contains no per-kind `case`.

### 8.3 Images

The proxy image is an `images.edn` entry with a fully qualified reference, resolved to a digest before launch
(N11-delta §5). The security gate checks the sidecar's launch plan as it checks the capsule's.

---

## 9. Events and Evidence

The following are **not yet N3 events or N6 artifact fields**. Registering them there is a prerequisite to
emitting them (SPEC_INDEX: wire contracts stay in N3 and N6), as for N11 §9.1.

*Proposed. Not emittable until registered.*

- Event `capsule/egress-refused`: run, task, host, port, reason. Throttled per N3 §4.2.
- Evidence keys: `:evidence/network-profile`, `:evidence/egress-allowlist` (hosts and their source per §3.1),
  `:evidence/egress-refused-hosts`, `:evidence/in-capsule-credentials` (names only, §5.5).

Credential values and tokens MUST NOT appear in either (N3 §8).

---

## 10. Kubernetes Targets

The contract of §1–§7 is substrate-agnostic. On Kubernetes:

1. The proxy runs in its own pod. Containers in one pod share a network namespace, so a NetworkPolicy cannot
   separate a capsule from a sidecar in the same pod.
2. A per-task NetworkPolicy denies all egress from the task pod except to its proxy pod, including DNS. The
   proxy pod's policy permits egress to public addresses and to the cluster's DNS service only.
3. NetworkPolicy takes effect only when the cluster's network plugin enforces it. Before reporting a capsule
   acquired under `:restricted` or `:standard`, the executor MUST show that a direct connection from the task
   pod to a public address fails.

The Kubernetes executor implements none of this. Until it does, a governed Kubernetes acquisition under
`:restricted` or `:standard` MUST fail with `:egress/unsupported-substrate` (N11.MD.1).

---

## 11. Amendments to N11

1. **§2.2 Network.** Deny-by-default is enforced by §1–§4 of this delta. Allowed endpoints are reached only
   through the capsule's proxy.
2. **§2.2 Credentials.** A substitutable credential is present in the capsule only as a token (§5).
3. **§5.1.** `:network-policy` becomes `{:profile #{:none :restricted :standard :full} :allow [{:host :port}]}`.
   `:proto` is dropped: the proxy carries HTTP and HTTPS only. `:mode :deny-default` with no `:allow` entries
   reads as `:none`. Either mode with entries reads as `:restricted`.
4. **§5.3.** The default for `:network-policy/profile` in governed mode is `:standard`.
5. **§8.1.** The sentence naming `ANTHROPIC_API_KEY` as injected into the capsule is replaced by §5.1: the
   capsule receives a token, and the value stays in the sidecar.
6. **§8.2.** The endpoint table becomes §3.1's derived and baseline hosts. Git over SSH (`github.com:22`) is
   not reachable through the proxy. Governed capsules clone over HTTPS.

---

## Conformance Requirements

| ID | Level | Requirement |
|----|-------|-------------|
| N11D2.EG.1 | MUST | A `:restricted` or `:standard` capsule has no network route except its proxy (§1, §2.1). |
| N11D2.EG.2 | MUST | Refuse `:full` in governed mode; default to `:standard` (§1). |
| N11D2.EG.3 | MUST | Fail acquisition, never weaken the profile, when the substrate cannot provide it (§2.3, §8.1, §10). |
| N11D2.EG.4 | MUST | Match hosts exactly; refuse every host on an empty list; check before any upstream connect (§3). |
| N11D2.EG.5 | MUST NOT | Let agent-produced artifacts add allowlist or private-allow entries (§3.1, §4). |
| N11D2.EG.6 | MUST | Refuse non-public dialed addresses, including embedded IPv4 forms; fail closed on bad input (§4). |
| N11D2.EG.7 | MUST | Give the capsule a token, never the value, for every substitutable agent credential (§5.1). |
| N11D2.EG.8 | MUST | Intercept TLS only on a credential's exact hosts; verify the upstream (§5.3). |
| N11D2.EG.9 | MUST | Redact credential values from responses without buffering streams (§5.4). |
| N11D2.EG.10 | MUST | Refuse non-substitutable and self-refreshing credentials unless the operator lists them (§5.5, §5.6). |
| N11D2.EG.11 | MUST | Use a per-capsule CA whose private key never leaves the sidecar (§6.1). |
| N11D2.EG.12 | MUST | State the profile, allowed hosts, and refusal handling in the agent context (§7.2). |
| N11D2.EG.13 | MUST NOT | Branch on `:runtime/kind` or call a daemon API for egress (§8). |

### Test Obligations

1. From a `:restricted` capsule, a direct TCP connection to a public address, with proxy variables unset, fails,
   and resolving a public name fails.
2. A CONNECT to an unlisted host gets `403`, and an upstream stub records no connection.
3. A capsule whose allowlist is empty refuses every host.
4. `169.254.169.254`, `::ffff:a9fe:a9fe`, `127.0.0.1`, `10.0.0.1`, and a name resolving to `10.0.0.1` are each
   refused by the guard.
5. `env` inside the capsule shows the token, not the value. A call to a stub credential host succeeds with the
   value substituted, and a response echoing the value arrives with the token in its place.
6. The Claude CLI and the Codex CLI each complete one call through the intercepting proxy with only §6.2's
   variables set.
7. On a runtime descriptor without `:network-modes/internal`, a `:restricted` acquisition fails and creates no
   container or network.
8. Release removes the capsule, sidecar, and network, and a second release succeeds.
9. A planner-generated task definition naming an extra host leaves the allowlist unchanged.
10. A governed Kubernetes acquisition under `:standard` fails with `:egress/unsupported-substrate`.

---

## Annex A — Current Implementation (informative)

This annex records the code as of `ea407dfc3` (2026-11-08). Paths are under
`components/dag-executor/src/ai/miniforge/dag_executor/` unless stated.

- **Profiles.** `build-security-args` (`protocols/impl/runtime/oci_cli/container.clj`) adds `--network=none`
  for `:none` and `:restricted`, and no network argument otherwise, so `:restricted` behaves as `:none`.
  `network-profiles` (`execution_plan.clj`) documents `:restricted` as an allow-list and `:standard` as
  default-deny with exceptions. Neither is enforced.
- **Governed default.** `acquire-execution-environment!`
  (`components/workflow/src/ai/miniforge/workflow/runner_environment.clj`) passes an env-config holding only
  `:repo-url`, `:branch`, and `:repo-path`. With no `:network-profile`, governed capsules run on the runtime's
  default network with unrestricted egress.
- **Destination guard.** `rg '169\.254|ssrf' components bases` finds nothing. No code blocks loopback, private,
  CGNAT, or link-local destinations.
- **Agent credentials.** `agent-credential-names` (`oci_cli/env.clj`) keeps `ANTHROPIC_API_KEY` through
  `filter-env-by-trust` at every trust level. `build-launch-plan` (`oci_cli/launch.clj`) adds the backend's
  `backend-credential-env` (`ANTHROPIC_API_KEY` and `CLAUDE_CODE_OAUTH_TOKEN` for `:claude`). A launch that
  carries one puts its value in the container env, where every process can read it. The governed workflow path
  above passes no `:env`, and `capsule-exec-fn`
  (`components/llm/src/ai/miniforge/llm/protocols/impl/llm_client/completion.clj`) refuses per-call env, so on
  that path the credential is not delivered through the plan.
- **Proxy variables.** `untrusted-env-blanks` sets every proxy variable to an empty value for an untrusted
  plan, and `create-container` passes them with `-e`. §2.1's routing replaces those values.
- **Registry.** `components/dag-executor/resources/config/dag-executor/runtime/registry.edn` advertises
  `:network-modes/none` and `:network-modes/bridge` for Docker and Podman. Neither capability of §8.1 is
  declared.
- **Kubernetes.** The Job spec in `protocols/impl/kubernetes.clj` carries no network restriction, and the
  executor creates no NetworkPolicy. `components/kubernetes-target` holds an identity schema only.

N11 Annex A.2 already lists network policy enforcement (§8.2) as specified and not implemented.

---

## Open Questions

- **Proxy implementation.** The sidecar needs a CONNECT proxy with selective TLS termination and streaming
  redaction. Whether it is a miniforge program (bb or JVM) in its own image, or an existing proxy configured
  per capsule, is open. §2–§7 bind its behavior either way.
- **Rootless Podman.** Internal networks and multi-network attach under rootless Podman with netavark need
  confirming per `(runtime, OS, arch)` triple before Podman advertises §8.1's capabilities.
- **Baseline contents.** §3.1's baseline list is to be agreed with the operator before `:standard` becomes the
  governed default.
- **CLI side traffic.** Agent CLIs contact hosts besides their API (telemetry, update checks). Those are refused
  unless listed. Whether `backends.edn` also carries each CLI's switch for turning such traffic off is open.

---

## References

- N11 §2.2, §5, §7.3, §8, §10 — base contract.
- N11-delta-runtime-adapter §2, §4, §5, §6 — runtime descriptor, capabilities, images, policies.
- N3 §4.2, §8 — throttling and credential redaction.
- PAODO-Workspaces `doc/adr/accepted/sandbox/adr-workspace-secrets-credential-proxy.md`,
  `adr-workspace-domain-allowlist.md`, `adr-workspace-internet-access-toggle.md`; `lib/infra/proxy/`
  `destinationGuard.ts` and `credentialProxy.ts`.

---

**Version History:**

- 0.1.0-draft (2026-11-08): Initial egress-control delta: network profiles, per-capsule proxy topology, exact-host
  allowlist, destination guard, credential substitution, per-capsule CA, refusal handling, registry mapping, and
  Kubernetes fail-closed rule.
