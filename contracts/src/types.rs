// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Core supervisory entity structs and enums.
//!
//! Field names use `serde(rename)` to map between Rust snake_case and the
//! Clojure namespaced-keyword JSON encoding (e.g. `"workflow/id"`).
//!
//! All structs carry a `#[serde(flatten)] extra: HashMap<String, Value>` field
//! so that unknown keys pass through without deserialization errors — matching
//! the open-map semantics of the upstream Malli schemas.

use std::collections::HashMap;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use uuid::Uuid;

// ============================================================================
// Enums — workflow
// ============================================================================

/// Workflow execution status.
///
/// Mirrors `ai.miniforge.schema.core/workflow-statuses`:
/// `[:pending :running :paused :completed :failed :cancelled]`
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum WorkflowStatus {
    Pending,
    Running,
    Paused,
    Completed,
    Failed,
    Cancelled,
}

/// Workflow phase within the outer-loop SDLC cycle.
///
/// Mirrors `ai.miniforge.schema.core/workflow-phases`:
/// `[:plan :design :implement :verify :review :release :observe]`
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum WorkflowPhase {
    Plan,
    Design,
    Implement,
    Verify,
    Review,
    Release,
    Observe,
}

// ============================================================================
// Enums — policy evaluation
// ============================================================================

/// Outcome of a policy evaluation.
///
/// Mirrors `ai.miniforge.schema.supervisory/eval-results`:
/// `[:pass :fail :warn :skip]`
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum EvalResult {
    Pass,
    Fail,
    Warn,
    Skip,
}

// ============================================================================
// Enums — severity / attention
// ============================================================================

/// Severity levels shared by violations and attention items.
///
/// Mirrors `ai.miniforge.schema.supervisory/violation-severities`:
/// `[:info :low :medium :high :critical]`
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Severity {
    Info,
    Low,
    Medium,
    High,
    Critical,
}

/// Source type that generated an attention item.
///
/// Mirrors `ai.miniforge.schema.supervisory/attention-source-types`:
/// `[:workflow :policy :agent :system :human]`
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum AttentionSourceType {
    Workflow,
    Policy,
    Agent,
    System,
    Human,
}

// ============================================================================
// Enums — agent session
// ============================================================================

/// Normalized agent status in the control plane.
///
/// Mirrors the statuses used in `ai.miniforge.control-plane.registry`:
/// `[:idle :starting :executing :blocked :completed :failed :unknown]`
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum AgentStatus {
    Idle,
    Starting,
    Executing,
    Blocked,
    Completed,
    Failed,
    Unknown,
}

// ============================================================================
// Enums — PR fleet
// ============================================================================

/// Risk level for a PR fleet entry.
///
/// Mirrors the risk values used in `tui-views.views.pr-fleet/risk-indicator`:
/// `[:low :medium :high :unevaluated]`
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PrRisk {
    Low,
    Medium,
    High,
    Unevaluated,
}

/// CI pipeline status for a PR.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum CiStatus {
    Pending,
    Running,
    Failed,
    Passed,
}

// ============================================================================
// Entity: WorkflowRun
// ============================================================================

/// A supervisory workflow run tracking one SDLC delivery lifecycle.
///
/// Corresponds to `ai.miniforge.schema.supervisory/WorkflowRun`.
///
/// Required fields:
/// - `workflow/id` — UUID
/// - `workflow/key` — reference to workflow spec (non-empty string)
/// - `workflow/status` — lifecycle status enum
/// - `workflow/phase` — current SDLC phase
/// - `workflow/started-at` — ISO 8601 timestamp
///
/// Open map: extra keys stored in `extra`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WorkflowRun {
    #[serde(rename = "workflow/id")]
    pub id: Uuid,

    #[serde(rename = "workflow/key")]
    pub key: String,

    #[serde(rename = "workflow/status")]
    pub status: WorkflowStatus,

    #[serde(rename = "workflow/phase")]
    pub phase: WorkflowPhase,

    #[serde(rename = "workflow/started-at")]
    pub started_at: DateTime<Utc>,

    /// Extra fields from the open Malli schema — pass through without validation.
    #[serde(flatten)]
    pub extra: HashMap<String, Value>,
}

// ============================================================================
// Entity: PolicyEvaluation
// ============================================================================

/// The outcome of evaluating policies against a PR or artifact.
///
/// Corresponds to `ai.miniforge.schema.supervisory/PolicyEvaluation`.
///
/// Required fields:
/// - `eval/id` — UUID
/// - `eval/pr-id` — PR identifier string (e.g. "repo/123")
/// - `eval/result` — pass/fail/warn/skip
/// - `eval/rules-applied` — list of rule identifiers
/// - `eval/evaluated-at` — ISO 8601 timestamp
///
/// Open map: extra keys stored in `extra`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PolicyEvaluation {
    #[serde(rename = "eval/id")]
    pub id: Uuid,

    #[serde(rename = "eval/pr-id")]
    pub pr_id: String,

    #[serde(rename = "eval/result")]
    pub result: EvalResult,

    #[serde(rename = "eval/rules-applied")]
    pub rules_applied: Vec<String>,

    #[serde(rename = "eval/evaluated-at")]
    pub evaluated_at: DateTime<Utc>,

    /// Extra fields from the open Malli schema.
    #[serde(flatten)]
    pub extra: HashMap<String, Value>,
}

// ============================================================================
// Entity: AttentionItem
// ============================================================================

/// An item requiring human review, surfaced from any supervisory source.
///
/// Corresponds to `ai.miniforge.schema.supervisory/AttentionItem`.
///
/// Required fields:
/// - `attention/id` — UUID
/// - `attention/severity` — info through critical
/// - `attention/summary` — one-line description
/// - `attention/source-type` — origin category
/// - `attention/source-id` — ID of the triggering entity
///
/// Open map: extra keys stored in `extra`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AttentionItem {
    #[serde(rename = "attention/id")]
    pub id: Uuid,

    #[serde(rename = "attention/severity")]
    pub severity: Severity,

    #[serde(rename = "attention/summary")]
    pub summary: String,

    #[serde(rename = "attention/source-type")]
    pub source_type: AttentionSourceType,

    #[serde(rename = "attention/source-id")]
    pub source_id: String,

    /// Extra fields from the open Malli schema.
    #[serde(flatten)]
    pub extra: HashMap<String, Value>,
}

// ============================================================================
// Entity: AgentSession
// ============================================================================

/// A registered agent session in the control plane.
///
/// Corresponds to the agent record created by
/// `ai.miniforge.control-plane.registry/register-agent!`.
///
/// Required fields:
/// - `agent/id` — control-plane-assigned UUID
/// - `agent/vendor` — adapter key (e.g. "claude-code")
/// - `agent/external-id` — vendor-specific session identifier
/// - `agent/name` — human-readable name
/// - `agent/status` — normalized lifecycle status
/// - `agent/registered-at` — ISO 8601 timestamp
/// - `agent/last-heartbeat` — ISO 8601 timestamp
///
/// Optional fields mirror the Clojure defaults from `register-agent!`.
/// Open map: extra keys stored in `extra`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AgentSession {
    #[serde(rename = "agent/id")]
    pub id: Uuid,

    #[serde(rename = "agent/vendor")]
    pub vendor: String,

    #[serde(rename = "agent/external-id")]
    pub external_id: String,

    #[serde(rename = "agent/name")]
    pub name: String,

    #[serde(rename = "agent/status")]
    pub status: AgentStatus,

    #[serde(rename = "agent/registered-at")]
    pub registered_at: DateTime<Utc>,

    #[serde(rename = "agent/last-heartbeat")]
    pub last_heartbeat: DateTime<Utc>,

    #[serde(rename = "agent/capabilities", default, skip_serializing_if = "Vec::is_empty")]
    pub capabilities: Vec<String>,

    #[serde(rename = "agent/heartbeat-interval-ms", default = "default_heartbeat_interval")]
    pub heartbeat_interval_ms: u64,

    #[serde(rename = "agent/metadata", default, skip_serializing_if = "HashMap::is_empty")]
    pub metadata: HashMap<String, Value>,

    #[serde(rename = "agent/tags", default, skip_serializing_if = "Vec::is_empty")]
    pub tags: Vec<String>,

    #[serde(rename = "agent/task", default, skip_serializing_if = "Option::is_none")]
    pub task: Option<String>,

    /// Extra fields from the open map.
    #[serde(flatten)]
    pub extra: HashMap<String, Value>,
}

fn default_heartbeat_interval() -> u64 {
    30000
}

// ============================================================================
// Entity: PrFleetEntry
// ============================================================================

/// A pull request tracked in the PR Fleet view.
///
/// Corresponds to the PR items rendered by
/// `ai.miniforge.tui-views.views.pr-fleet/format-pr-row`.
///
/// Required fields:
/// - `pr/title` — PR title
/// - `pr/repo` — repository identifier (e.g. "owner/repo")
///
/// Optional fields:
/// - `pr/number` — PR number within the repo
/// - `pr/readiness` — float 0.0–1.0 completion score
/// - `pr/risk` — risk level enum
/// - `pr/status` — PR status string (e.g. "open", "merged")
/// - `pr/ci-status` — CI pipeline status
/// - `pr/depends-on` — list of blocking PR identifiers
///
/// Open map: extra keys stored in `extra`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PrFleetEntry {
    #[serde(rename = "pr/title")]
    pub title: String,

    #[serde(rename = "pr/repo")]
    pub repo: String,

    #[serde(rename = "pr/number", default, skip_serializing_if = "Option::is_none")]
    pub number: Option<u64>,

    #[serde(rename = "pr/readiness", default, skip_serializing_if = "Option::is_none")]
    pub readiness: Option<f64>,

    #[serde(rename = "pr/risk", default, skip_serializing_if = "Option::is_none")]
    pub risk: Option<PrRisk>,

    #[serde(rename = "pr/status", default, skip_serializing_if = "Option::is_none")]
    pub status: Option<String>,

    #[serde(rename = "pr/ci-status", default, skip_serializing_if = "Option::is_none")]
    pub ci_status: Option<CiStatus>,

    #[serde(rename = "pr/depends-on", default, skip_serializing_if = "Vec::is_empty")]
    pub depends_on: Vec<String>,

    /// Extra fields from the open map.
    #[serde(flatten)]
    pub extra: HashMap<String, Value>,
}

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    // ------------------------------------------------------------------
    // WorkflowRun
    // ------------------------------------------------------------------

    #[test]
    fn workflow_run_roundtrip() {
        let id = Uuid::new_v4();
        let now = Utc::now();
        let wf = WorkflowRun {
            id,
            key: "feature-auth".into(),
            status: WorkflowStatus::Running,
            phase: WorkflowPhase::Implement,
            started_at: now,
            extra: HashMap::new(),
        };
        let json = serde_json::to_string(&wf).unwrap();
        let roundtripped: WorkflowRun = serde_json::from_str(&json).unwrap();
        assert_eq!(wf.id, roundtripped.id);
        assert_eq!(wf.status, roundtripped.status);
        assert_eq!(wf.phase, roundtripped.phase);
    }

    #[test]
    fn workflow_run_extra_fields_pass_through() {
        let json = json!({
            "workflow/id": "550e8400-e29b-41d4-a716-446655440000",
            "workflow/key": "feature-auth",
            "workflow/status": "running",
            "workflow/phase": "implement",
            "workflow/started-at": "2026-04-10T12:00:00Z",
            "custom/annotation": "allowed by open map"
        });
        let wf: WorkflowRun = serde_json::from_value(json).unwrap();
        assert_eq!(wf.key, "feature-auth");
        assert!(wf.extra.contains_key("custom/annotation"));
    }

    #[test]
    fn workflow_run_all_statuses() {
        for status in ["pending", "running", "paused", "completed", "failed", "cancelled"] {
            let json = json!({
                "workflow/id": "550e8400-e29b-41d4-a716-446655440000",
                "workflow/key": "test",
                "workflow/status": status,
                "workflow/phase": "plan",
                "workflow/started-at": "2026-04-10T00:00:00Z"
            });
            let wf: WorkflowRun = serde_json::from_value(json).unwrap();
            assert_eq!(format!("{:?}", wf.status).to_lowercase(), status.replace("-", ""));
        }
    }

    #[test]
    fn workflow_run_all_phases() {
        for phase in ["plan", "design", "implement", "verify", "review", "release", "observe"] {
            let json = json!({
                "workflow/id": "550e8400-e29b-41d4-a716-446655440000",
                "workflow/key": "test",
                "workflow/status": "pending",
                "workflow/phase": phase,
                "workflow/started-at": "2026-04-10T00:00:00Z"
            });
            let wf: WorkflowRun = serde_json::from_value(json).unwrap();
            assert_eq!(format!("{:?}", wf.phase).to_lowercase(), phase);
        }
    }

    // ------------------------------------------------------------------
    // PolicyEvaluation
    // ------------------------------------------------------------------

    #[test]
    fn policy_evaluation_roundtrip() {
        let pe = PolicyEvaluation {
            id: Uuid::new_v4(),
            pr_id: "miniforge/ai#42".into(),
            result: EvalResult::Pass,
            rules_applied: vec!["no-force-push".into(), "require-tests".into()],
            evaluated_at: Utc::now(),
            extra: HashMap::new(),
        };
        let json = serde_json::to_string(&pe).unwrap();
        let rt: PolicyEvaluation = serde_json::from_str(&json).unwrap();
        assert_eq!(pe.id, rt.id);
        assert_eq!(pe.result, rt.result);
        assert_eq!(pe.rules_applied, rt.rules_applied);
    }

    #[test]
    fn policy_evaluation_all_results() {
        for result in ["pass", "fail", "warn", "skip"] {
            let json = json!({
                "eval/id": "550e8400-e29b-41d4-a716-446655440001",
                "eval/pr-id": "repo/123",
                "eval/result": result,
                "eval/rules-applied": ["rule-a"],
                "eval/evaluated-at": "2026-04-10T00:00:00Z"
            });
            let pe: PolicyEvaluation = serde_json::from_value(json).unwrap();
            assert_eq!(format!("{:?}", pe.result).to_lowercase(), result);
        }
    }

    #[test]
    fn policy_evaluation_extra_fields() {
        let json = json!({
            "eval/id": "550e8400-e29b-41d4-a716-446655440001",
            "eval/pr-id": "repo/123",
            "eval/result": "pass",
            "eval/rules-applied": ["no-force-push", "require-tests"],
            "eval/evaluated-at": "2026-04-10T00:00:00Z",
            "policy-eval/passed?": true,
            "policy-eval/packs-applied": ["security-v2"]
        });
        let pe: PolicyEvaluation = serde_json::from_value(json).unwrap();
        assert_eq!(pe.extra.get("policy-eval/passed?"), Some(&json!(true)));
    }

    // ------------------------------------------------------------------
    // AttentionItem
    // ------------------------------------------------------------------

    #[test]
    fn attention_item_roundtrip() {
        let ai = AttentionItem {
            id: Uuid::new_v4(),
            severity: Severity::High,
            summary: "PR #42 has 3 unresolved policy violations".into(),
            source_type: AttentionSourceType::Policy,
            source_id: "550e8400-e29b-41d4-a716-446655440001".into(),
            extra: HashMap::new(),
        };
        let json = serde_json::to_string(&ai).unwrap();
        let rt: AttentionItem = serde_json::from_str(&json).unwrap();
        assert_eq!(ai.id, rt.id);
        assert_eq!(ai.severity, rt.severity);
        assert_eq!(ai.source_type, rt.source_type);
    }

    #[test]
    fn attention_item_all_severities() {
        for sev in ["info", "low", "medium", "high", "critical"] {
            let json = json!({
                "attention/id": "550e8400-e29b-41d4-a716-446655440002",
                "attention/severity": sev,
                "attention/summary": "test",
                "attention/source-type": "system",
                "attention/source-id": "sys-1"
            });
            let item: AttentionItem = serde_json::from_value(json).unwrap();
            assert_eq!(format!("{:?}", item.severity).to_lowercase(), sev);
        }
    }

    #[test]
    fn attention_item_all_source_types() {
        for src in ["workflow", "policy", "agent", "system", "human"] {
            let json = json!({
                "attention/id": "550e8400-e29b-41d4-a716-446655440002",
                "attention/severity": "info",
                "attention/summary": "test",
                "attention/source-type": src,
                "attention/source-id": "id-1"
            });
            let item: AttentionItem = serde_json::from_value(json).unwrap();
            assert_eq!(format!("{:?}", item.source_type).to_lowercase(), src);
        }
    }

    // ------------------------------------------------------------------
    // AgentSession
    // ------------------------------------------------------------------

    #[test]
    fn agent_session_roundtrip() {
        let sess = AgentSession {
            id: Uuid::new_v4(),
            vendor: "claude-code".into(),
            external_id: "session-abc-123".into(),
            name: "PR Review Agent".into(),
            status: AgentStatus::Executing,
            registered_at: Utc::now(),
            last_heartbeat: Utc::now(),
            capabilities: vec!["code".into(), "test".into()],
            heartbeat_interval_ms: 30000,
            metadata: HashMap::new(),
            tags: vec![],
            task: Some("Reviewing PR #42".into()),
            extra: HashMap::new(),
        };
        let json = serde_json::to_string(&sess).unwrap();
        let rt: AgentSession = serde_json::from_str(&json).unwrap();
        assert_eq!(sess.id, rt.id);
        assert_eq!(sess.vendor, rt.vendor);
        assert_eq!(sess.status, rt.status);
        assert_eq!(sess.task, rt.task);
    }

    #[test]
    fn agent_session_minimal() {
        let json = json!({
            "agent/id": "550e8400-e29b-41d4-a716-446655440003",
            "agent/vendor": "claude-code",
            "agent/external-id": "session-xyz",
            "agent/name": "Test Agent",
            "agent/status": "idle",
            "agent/registered-at": "2026-04-10T08:00:00Z",
            "agent/last-heartbeat": "2026-04-10T08:00:00Z"
        });
        let sess: AgentSession = serde_json::from_value(json).unwrap();
        assert_eq!(sess.vendor, "claude-code");
        assert_eq!(sess.status, AgentStatus::Idle);
        assert_eq!(sess.heartbeat_interval_ms, 30000); // default
        assert!(sess.capabilities.is_empty());
        assert!(sess.tags.is_empty());
        assert!(sess.task.is_none());
    }

    #[test]
    fn agent_session_all_statuses() {
        for status in ["idle", "starting", "executing", "blocked", "completed", "failed", "unknown"] {
            let json = json!({
                "agent/id": "550e8400-e29b-41d4-a716-446655440003",
                "agent/vendor": "claude-code",
                "agent/external-id": "s1",
                "agent/name": "Test",
                "agent/status": status,
                "agent/registered-at": "2026-04-10T00:00:00Z",
                "agent/last-heartbeat": "2026-04-10T00:00:00Z"
            });
            let sess: AgentSession = serde_json::from_value(json).unwrap();
            assert_eq!(format!("{:?}", sess.status).to_lowercase(), status);
        }
    }

    // ------------------------------------------------------------------
    // PrFleetEntry
    // ------------------------------------------------------------------

    #[test]
    fn pr_fleet_entry_roundtrip() {
        let pr = PrFleetEntry {
            title: "Add supervisory contracts".into(),
            repo: "miniforge/ai".into(),
            number: Some(42),
            readiness: Some(0.75),
            risk: Some(PrRisk::Medium),
            status: Some("open".into()),
            ci_status: Some(CiStatus::Passed),
            depends_on: vec!["miniforge/ai#41".into()],
            extra: HashMap::new(),
        };
        let json = serde_json::to_string(&pr).unwrap();
        let rt: PrFleetEntry = serde_json::from_str(&json).unwrap();
        assert_eq!(pr.title, rt.title);
        assert_eq!(pr.readiness, rt.readiness);
        assert_eq!(pr.risk, rt.risk);
    }

    #[test]
    fn pr_fleet_entry_minimal() {
        let json = json!({
            "pr/title": "Fix typo in README",
            "pr/repo": "miniforge/docs"
        });
        let pr: PrFleetEntry = serde_json::from_value(json).unwrap();
        assert_eq!(pr.title, "Fix typo in README");
        assert_eq!(pr.repo, "miniforge/docs");
        assert!(pr.number.is_none());
        assert!(pr.readiness.is_none());
        assert!(pr.risk.is_none());
        assert!(pr.ci_status.is_none());
        assert!(pr.depends_on.is_empty());
    }

    #[test]
    fn pr_fleet_entry_all_risks() {
        for risk in ["low", "medium", "high", "unevaluated"] {
            let json = json!({
                "pr/title": "Test PR",
                "pr/repo": "test/repo",
                "pr/risk": risk
            });
            let pr: PrFleetEntry = serde_json::from_value(json).unwrap();
            assert!(pr.risk.is_some());
        }
    }

    #[test]
    fn pr_fleet_entry_extra_fields() {
        let json = json!({
            "pr/title": "Feature X",
            "pr/repo": "org/repo",
            "pr/train-id": "train-alpha",
            "custom/priority": 1
        });
        let pr: PrFleetEntry = serde_json::from_value(json).unwrap();
        assert_eq!(pr.extra.get("pr/train-id"), Some(&json!("train-alpha")));
        assert_eq!(pr.extra.get("custom/priority"), Some(&json!(1)));
    }
}
