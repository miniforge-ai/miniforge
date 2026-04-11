// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
// Licensed under the Apache License, Version 2.0

//! Canonical supervisory entity definitions per N5-delta §3.
//!
//! Field names use Clojure-style namespaced keywords (`namespace/field`)
//! via serde `rename` attributes so JSON produced by the Clojure event
//! stream round-trips without transformation.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

// ---------------------------------------------------------------------------
// WorkflowRun  (N5-delta §3.1)
// ---------------------------------------------------------------------------

/// Workflow execution status per N5-delta §3.2.
///
/// Terminal states (`Completed`, `Failed`, `Cancelled`) must not
/// transition back to active states. A retry produces a new run.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum WorkflowRunStatus {
    Queued,
    Running,
    Paused,
    Blocked,
    Completed,
    Failed,
    Cancelled,
}

/// How the workflow was triggered.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum TriggerSource {
    Mcp,
    Cli,
    Api,
    Chain,
}

/// A concrete execution instance of a workflow (N5-delta §3.1).
///
/// Maps to the Clojure `:workflow-run/*` namespaced keys.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WorkflowRun {
    #[serde(rename = "workflow-run/id")]
    pub id: Uuid,

    #[serde(rename = "workflow-run/workflow-key")]
    pub workflow_key: String,

    #[serde(rename = "workflow-run/intent")]
    pub intent: String,

    #[serde(rename = "workflow-run/status")]
    pub status: WorkflowRunStatus,

    #[serde(rename = "workflow-run/current-phase")]
    pub current_phase: String,

    #[serde(rename = "workflow-run/started-at")]
    pub started_at: DateTime<Utc>,

    #[serde(rename = "workflow-run/updated-at")]
    pub updated_at: DateTime<Utc>,

    #[serde(rename = "workflow-run/trigger-source")]
    pub trigger_source: TriggerSource,

    #[serde(rename = "workflow-run/correlation-id")]
    pub correlation_id: Uuid,
}

// ---------------------------------------------------------------------------
// PolicyViolation  (N5-delta §3.1)
// ---------------------------------------------------------------------------

/// Severity of a policy violation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ViolationSeverity {
    Critical,
    High,
    Medium,
    Low,
    Info,
}

/// A single rule failure within a PolicyEvaluation (N5-delta §3.1).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PolicyViolation {
    #[serde(rename = "violation/rule-id")]
    pub rule_id: String,

    #[serde(rename = "violation/severity")]
    pub severity: ViolationSeverity,

    #[serde(rename = "violation/category")]
    pub category: String,

    #[serde(rename = "violation/message")]
    pub message: String,

    #[serde(
        rename = "violation/location",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub location: Option<String>,

    #[serde(rename = "violation/remediable?")]
    pub remediable: bool,
}

// ---------------------------------------------------------------------------
// PolicyEvaluation  (N5-delta §3.1)
// ---------------------------------------------------------------------------

/// What the policy was evaluated against.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PolicyTargetType {
    Pr,
    Artifact,
    WorkflowOutput,
}

/// An immutable record of a completed policy evaluation (N5-delta §3.1).
///
/// A re-evaluation produces a *new* record; existing records are never mutated.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PolicyEvaluation {
    #[serde(rename = "policy-eval/id")]
    pub id: Uuid,

    #[serde(rename = "policy-eval/target-type")]
    pub target_type: PolicyTargetType,

    /// For PRs this is typically `"[owner/repo 42]"` serialised;
    /// for others it is a UUID string.
    #[serde(rename = "policy-eval/target-id")]
    pub target_id: serde_json::Value,

    #[serde(rename = "policy-eval/passed?")]
    pub passed: bool,

    #[serde(rename = "policy-eval/packs-applied")]
    pub packs_applied: Vec<String>,

    #[serde(rename = "policy-eval/violations")]
    pub violations: Vec<PolicyViolation>,

    #[serde(rename = "policy-eval/evaluated-at")]
    pub evaluated_at: DateTime<Utc>,
}

// ---------------------------------------------------------------------------
// AttentionItem  (N5-delta §3.1)
// ---------------------------------------------------------------------------

/// Attention severity.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum AttentionSeverity {
    Critical,
    Warning,
    Info,
}

/// What kind of entity sourced the attention signal.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum AttentionSourceType {
    Workflow,
    Pr,
    Train,
    Policy,
    Agent,
}

/// A derived supervisory signal indicating something needs human
/// awareness or action (N5-delta §3.1, §5).
///
/// Attention items are derivable from canonical state; they may be
/// cached but the underlying entity state is authoritative.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AttentionItem {
    #[serde(rename = "attention/id")]
    pub id: Uuid,

    #[serde(rename = "attention/severity")]
    pub severity: AttentionSeverity,

    #[serde(rename = "attention/source-type")]
    pub source_type: AttentionSourceType,

    #[serde(rename = "attention/source-id")]
    pub source_id: serde_json::Value,

    #[serde(rename = "attention/summary")]
    pub summary: String,

    #[serde(rename = "attention/derived-at")]
    pub derived_at: DateTime<Utc>,

    #[serde(rename = "attention/resolved?")]
    pub resolved: bool,
}

// ---------------------------------------------------------------------------
// AgentSession  (N5-delta §3.3 / control-plane registry)
// ---------------------------------------------------------------------------

/// Agent lifecycle status (from control-plane/registry).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum AgentStatus {
    Unknown,
    Running,
    Blocked,
    Completed,
    Failed,
    Terminated,
    Unreachable,
}

/// An agent session record as tracked by the control-plane registry
/// (N5-delta §3.3, §10).
///
/// Maps to the `:agent/*` namespaced keys in the Clojure registry.
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

    #[serde(
        rename = "agent/capabilities",
        default,
        skip_serializing_if = "Vec::is_empty"
    )]
    pub capabilities: Vec<String>,

    #[serde(
        rename = "agent/heartbeat-interval-ms",
        default = "default_heartbeat_interval"
    )]
    pub heartbeat_interval_ms: u64,

    #[serde(
        rename = "agent/metadata",
        default,
        skip_serializing_if = "serde_json::Map::is_empty"
    )]
    pub metadata: serde_json::Map<String, serde_json::Value>,

    #[serde(
        rename = "agent/tags",
        default,
        skip_serializing_if = "Vec::is_empty"
    )]
    pub tags: Vec<String>,

    #[serde(rename = "agent/registered-at")]
    pub registered_at: DateTime<Utc>,

    #[serde(rename = "agent/last-heartbeat")]
    pub last_heartbeat: DateTime<Utc>,

    #[serde(
        rename = "agent/task",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub task: Option<String>,
}

fn default_heartbeat_interval() -> u64 {
    30000
}

// ---------------------------------------------------------------------------
// PrFleetEntry  (N5-delta §3.3 / pr-train schema TrainPR)
// ---------------------------------------------------------------------------

/// CI pipeline status for a PR.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum CiStatus {
    Pending,
    Running,
    Passed,
    Failed,
    Skipped,
}

/// PR lifecycle status within a fleet/train.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PrStatus {
    Draft,
    Open,
    Reviewing,
    ChangesRequested,
    Approved,
    Merging,
    Merged,
    Closed,
    Failed,
}

/// A single PR entry within the fleet or train (N5-delta §3.3, N9).
///
/// Maps to the `:pr/*` namespaced keys used by `pr-train/schema`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PrFleetEntry {
    #[serde(rename = "pr/repo")]
    pub repo: String,

    #[serde(rename = "pr/number")]
    pub number: u64,

    #[serde(rename = "pr/url")]
    pub url: String,

    #[serde(rename = "pr/branch")]
    pub branch: String,

    #[serde(rename = "pr/title")]
    pub title: String,

    #[serde(rename = "pr/status")]
    pub status: PrStatus,

    #[serde(rename = "pr/merge-order")]
    pub merge_order: u64,

    #[serde(rename = "pr/depends-on", default)]
    pub depends_on: Vec<u64>,

    #[serde(rename = "pr/blocks", default)]
    pub blocks: Vec<u64>,

    #[serde(rename = "pr/ci-status")]
    pub ci_status: CiStatus,

    #[serde(rename = "pr/author", default, skip_serializing_if = "Option::is_none")]
    pub author: Option<String>,

    #[serde(
        rename = "pr/additions",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub additions: Option<u64>,

    #[serde(
        rename = "pr/deletions",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub deletions: Option<u64>,

    #[serde(
        rename = "pr/changed-files-count",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub changed_files_count: Option<u64>,

    #[serde(
        rename = "pr/behind-main?",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub behind_main: Option<bool>,

    #[serde(
        rename = "pr/merged-at",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub merged_at: Option<DateTime<Utc>>,
}

// ---------------------------------------------------------------------------
// Unit tests — JSON fixture round-trips
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// Helper: assert a value round-trips through JSON without loss.
    fn assert_round_trip<T>(val: &T)
    where
        T: Serialize + for<'de> Deserialize<'de> + PartialEq + std::fmt::Debug,
    {
        let json_str = serde_json::to_string(val).expect("serialize");
        let back: T = serde_json::from_str(&json_str).expect("deserialize");
        assert_eq!(val, &back, "round-trip mismatch");
    }

    // -----------------------------------------------------------------------
    // WorkflowRun
    // -----------------------------------------------------------------------

    #[test]
    fn test_workflow_run_from_clojure_json() {
        // Simulates JSON as produced by the Clojure event stream sink
        let fixture = json!({
            "workflow-run/id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            "workflow-run/workflow-key": "implement-feature",
            "workflow-run/intent": "Add user authentication module",
            "workflow-run/status": "running",
            "workflow-run/current-phase": "implement",
            "workflow-run/started-at": "2026-04-10T14:30:00Z",
            "workflow-run/updated-at": "2026-04-10T14:35:22Z",
            "workflow-run/trigger-source": "cli",
            "workflow-run/correlation-id": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        });

        let run: WorkflowRun = serde_json::from_value(fixture).expect("deserialize WorkflowRun");
        assert_eq!(run.workflow_key, "implement-feature");
        assert_eq!(run.status, WorkflowRunStatus::Running);
        assert_eq!(run.current_phase, "implement");
        assert_eq!(run.trigger_source, TriggerSource::Cli);
        assert_round_trip(&run);
    }

    #[test]
    fn test_workflow_run_all_statuses() {
        let statuses = [
            ("queued", WorkflowRunStatus::Queued),
            ("running", WorkflowRunStatus::Running),
            ("paused", WorkflowRunStatus::Paused),
            ("blocked", WorkflowRunStatus::Blocked),
            ("completed", WorkflowRunStatus::Completed),
            ("failed", WorkflowRunStatus::Failed),
            ("cancelled", WorkflowRunStatus::Cancelled),
        ];
        for (json_str, expected) in &statuses {
            let v: WorkflowRunStatus =
                serde_json::from_str(&format!("\"{}\"", json_str)).expect(json_str);
            assert_eq!(&v, expected);
        }
    }

    // -----------------------------------------------------------------------
    // PolicyEvaluation
    // -----------------------------------------------------------------------

    #[test]
    fn test_policy_evaluation_from_clojure_json() {
        let fixture = json!({
            "policy-eval/id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
            "policy-eval/target-type": "pr",
            "policy-eval/target-id": ["miniforge-ai/miniforge", 42],
            "policy-eval/passed?": false,
            "policy-eval/packs-applied": ["core-safety", "code-quality"],
            "policy-eval/violations": [
                {
                    "violation/rule-id": "no-force-push",
                    "violation/severity": "critical",
                    "violation/category": "git-safety",
                    "violation/message": "Force push to protected branch detected",
                    "violation/location": "refs/heads/main",
                    "violation/remediable?": false
                },
                {
                    "violation/rule-id": "test-coverage-min",
                    "violation/severity": "high",
                    "violation/category": "testing",
                    "violation/message": "Test coverage below 80% threshold (72%)",
                    "violation/remediable?": true
                }
            ],
            "policy-eval/evaluated-at": "2026-04-10T15:00:00Z"
        });

        let eval: PolicyEvaluation =
            serde_json::from_value(fixture).expect("deserialize PolicyEvaluation");
        assert!(!eval.passed);
        assert_eq!(eval.packs_applied.len(), 2);
        assert_eq!(eval.violations.len(), 2);
        assert_eq!(eval.violations[0].severity, ViolationSeverity::Critical);
        assert!(!eval.violations[0].remediable);
        assert!(eval.violations[1].remediable);
        assert_eq!(eval.violations[1].location, None);
        assert_eq!(eval.target_type, PolicyTargetType::Pr);
        assert_round_trip(&eval);
    }

    #[test]
    fn test_policy_evaluation_passing() {
        let fixture = json!({
            "policy-eval/id": "c3d4e5f6-a7b8-9012-cdef-123456789012",
            "policy-eval/target-type": "artifact",
            "policy-eval/target-id": "d4e5f6a7-b8c9-0123-def1-234567890123",
            "policy-eval/passed?": true,
            "policy-eval/packs-applied": ["core-safety"],
            "policy-eval/violations": [],
            "policy-eval/evaluated-at": "2026-04-10T16:00:00Z"
        });

        let eval: PolicyEvaluation =
            serde_json::from_value(fixture).expect("deserialize passing PolicyEvaluation");
        assert!(eval.passed);
        assert!(eval.violations.is_empty());
        assert_eq!(eval.target_type, PolicyTargetType::Artifact);
        assert_round_trip(&eval);
    }

    // -----------------------------------------------------------------------
    // AttentionItem
    // -----------------------------------------------------------------------

    #[test]
    fn test_attention_item_from_clojure_json() {
        let fixture = json!({
            "attention/id": "d4e5f6a7-b8c9-0123-def1-234567890123",
            "attention/severity": "critical",
            "attention/source-type": "workflow",
            "attention/source-id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            "attention/summary": "Workflow implement-feature failed: agent timeout",
            "attention/derived-at": "2026-04-10T15:10:00Z",
            "attention/resolved?": false
        });

        let item: AttentionItem =
            serde_json::from_value(fixture).expect("deserialize AttentionItem");
        assert_eq!(item.severity, AttentionSeverity::Critical);
        assert_eq!(item.source_type, AttentionSourceType::Workflow);
        assert!(!item.resolved);
        assert!(item.summary.contains("agent timeout"));
        assert_round_trip(&item);
    }

    #[test]
    fn test_attention_item_resolved_info() {
        let fixture = json!({
            "attention/id": "e5f6a7b8-c9d0-1234-ef12-345678901234",
            "attention/severity": "info",
            "attention/source-type": "pr",
            "attention/source-id": ["miniforge-ai/miniforge", 99],
            "attention/summary": "PR miniforge-ai/miniforge#99 merged",
            "attention/derived-at": "2026-04-10T16:30:00Z",
            "attention/resolved?": true
        });

        let item: AttentionItem =
            serde_json::from_value(fixture).expect("deserialize resolved attention");
        assert_eq!(item.severity, AttentionSeverity::Info);
        assert!(item.resolved);
        assert_round_trip(&item);
    }

    // -----------------------------------------------------------------------
    // AgentSession
    // -----------------------------------------------------------------------

    #[test]
    fn test_agent_session_from_clojure_json() {
        let fixture = json!({
            "agent/id": "f6a7b8c9-d0e1-2345-f123-456789012345",
            "agent/vendor": "claude-code",
            "agent/external-id": "/Users/chris/.claude/projects/miniforge",
            "agent/name": "Claude Code (miniforge)",
            "agent/status": "running",
            "agent/capabilities": ["implement", "test", "review"],
            "agent/heartbeat-interval-ms": 30000,
            "agent/metadata": {
                "model": "claude-sonnet-4-20250514",
                "pid": 12345
            },
            "agent/tags": ["primary", "local"],
            "agent/registered-at": "2026-04-10T14:00:00Z",
            "agent/last-heartbeat": "2026-04-10T15:05:00Z",
            "agent/task": "Implementing auth module"
        });

        let session: AgentSession =
            serde_json::from_value(fixture).expect("deserialize AgentSession");
        assert_eq!(session.vendor, "claude-code");
        assert_eq!(session.status, AgentStatus::Running);
        assert_eq!(session.capabilities.len(), 3);
        assert_eq!(session.heartbeat_interval_ms, 30000);
        assert_eq!(session.tags.len(), 2);
        assert_eq!(session.task, Some("Implementing auth module".to_string()));
        assert!(session.metadata.contains_key("model"));
        assert_round_trip(&session);
    }

    #[test]
    fn test_agent_session_minimal() {
        // Minimal agent with only required fields
        let fixture = json!({
            "agent/id": "a7b8c9d0-e1f2-3456-1234-567890123456",
            "agent/vendor": "codex",
            "agent/external-id": "codex-session-abc",
            "agent/name": "Codex Agent",
            "agent/status": "unknown",
            "agent/registered-at": "2026-04-10T14:00:00Z",
            "agent/last-heartbeat": "2026-04-10T14:00:00Z"
        });

        let session: AgentSession =
            serde_json::from_value(fixture).expect("deserialize minimal AgentSession");
        assert_eq!(session.status, AgentStatus::Unknown);
        assert!(session.capabilities.is_empty());
        assert_eq!(session.heartbeat_interval_ms, 30000); // default
        assert!(session.tags.is_empty());
        assert!(session.task.is_none());
        assert_round_trip(&session);
    }

    #[test]
    fn test_agent_session_all_statuses() {
        let statuses = [
            ("unknown", AgentStatus::Unknown),
            ("running", AgentStatus::Running),
            ("blocked", AgentStatus::Blocked),
            ("completed", AgentStatus::Completed),
            ("failed", AgentStatus::Failed),
            ("terminated", AgentStatus::Terminated),
            ("unreachable", AgentStatus::Unreachable),
        ];
        for (json_str, expected) in &statuses {
            let v: AgentStatus =
                serde_json::from_str(&format!("\"{}\"", json_str)).expect(json_str);
            assert_eq!(&v, expected);
        }
    }

    // -----------------------------------------------------------------------
    // PrFleetEntry
    // -----------------------------------------------------------------------

    #[test]
    fn test_pr_fleet_entry_from_clojure_json() {
        let fixture = json!({
            "pr/repo": "miniforge-ai/miniforge",
            "pr/number": 42,
            "pr/url": "https://github.com/miniforge-ai/miniforge/pull/42",
            "pr/branch": "feature/auth-module",
            "pr/title": "Add user authentication module",
            "pr/status": "reviewing",
            "pr/merge-order": 1,
            "pr/depends-on": [],
            "pr/blocks": [43, 44],
            "pr/ci-status": "passed",
            "pr/author": "agent-claude",
            "pr/additions": 350,
            "pr/deletions": 12,
            "pr/changed-files-count": 8,
            "pr/behind-main?": false
        });

        let entry: PrFleetEntry =
            serde_json::from_value(fixture).expect("deserialize PrFleetEntry");
        assert_eq!(entry.repo, "miniforge-ai/miniforge");
        assert_eq!(entry.number, 42);
        assert_eq!(entry.status, PrStatus::Reviewing);
        assert_eq!(entry.ci_status, CiStatus::Passed);
        assert_eq!(entry.blocks, vec![43, 44]);
        assert!(entry.depends_on.is_empty());
        assert_eq!(entry.author, Some("agent-claude".to_string()));
        assert_eq!(entry.additions, Some(350));
        assert_eq!(entry.behind_main, Some(false));
        assert!(entry.merged_at.is_none());
        assert_round_trip(&entry);
    }

    #[test]
    fn test_pr_fleet_entry_merged() {
        let fixture = json!({
            "pr/repo": "miniforge-ai/miniforge",
            "pr/number": 40,
            "pr/url": "https://github.com/miniforge-ai/miniforge/pull/40",
            "pr/branch": "fix/typo",
            "pr/title": "Fix README typo",
            "pr/status": "merged",
            "pr/merge-order": 1,
            "pr/depends-on": [],
            "pr/blocks": [],
            "pr/ci-status": "passed",
            "pr/merged-at": "2026-04-09T12:00:00Z"
        });

        let entry: PrFleetEntry =
            serde_json::from_value(fixture).expect("deserialize merged PrFleetEntry");
        assert_eq!(entry.status, PrStatus::Merged);
        assert!(entry.merged_at.is_some());
        assert!(entry.author.is_none());
        assert_round_trip(&entry);
    }

    #[test]
    fn test_pr_fleet_entry_all_statuses() {
        let statuses = [
            ("draft", PrStatus::Draft),
            ("open", PrStatus::Open),
            ("reviewing", PrStatus::Reviewing),
            ("changes-requested", PrStatus::ChangesRequested),
            ("approved", PrStatus::Approved),
            ("merging", PrStatus::Merging),
            ("merged", PrStatus::Merged),
            ("closed", PrStatus::Closed),
            ("failed", PrStatus::Failed),
        ];
        for (json_str, expected) in &statuses {
            let v: PrStatus =
                serde_json::from_str(&format!("\"{}\"", json_str)).expect(json_str);
            assert_eq!(&v, expected);
        }
    }

    #[test]
    fn test_pr_fleet_entry_all_ci_statuses() {
        let statuses = [
            ("pending", CiStatus::Pending),
            ("running", CiStatus::Running),
            ("passed", CiStatus::Passed),
            ("failed", CiStatus::Failed),
            ("skipped", CiStatus::Skipped),
        ];
        for (json_str, expected) in &statuses {
            let v: CiStatus =
                serde_json::from_str(&format!("\"{}\"", json_str)).expect(json_str);
            assert_eq!(&v, expected);
        }
    }

    // -----------------------------------------------------------------------
    // Cross-entity: supervisory snapshot
    // -----------------------------------------------------------------------

    #[test]
    fn test_supervisory_snapshot_array() {
        // Verify we can deserialize a heterogeneous supervisory payload
        // (e.g. a TUI monitor refresh response containing all entity types)
        let workflow_json = json!({
            "workflow-run/id": "11111111-1111-1111-1111-111111111111",
            "workflow-run/workflow-key": "scan",
            "workflow-run/intent": "Scan repository for tech fingerprints",
            "workflow-run/status": "completed",
            "workflow-run/current-phase": "done",
            "workflow-run/started-at": "2026-04-10T10:00:00Z",
            "workflow-run/updated-at": "2026-04-10T10:05:00Z",
            "workflow-run/trigger-source": "mcp",
            "workflow-run/correlation-id": "22222222-2222-2222-2222-222222222222"
        });
        let attention_json = json!({
            "attention/id": "33333333-3333-3333-3333-333333333333",
            "attention/severity": "warning",
            "attention/source-type": "train",
            "attention/source-id": "train-alpha",
            "attention/summary": "Train blocked at miniforge-ai/miniforge#42: merge conflict",
            "attention/derived-at": "2026-04-10T10:06:00Z",
            "attention/resolved?": false
        });

        let wf: WorkflowRun = serde_json::from_value(workflow_json).unwrap();
        let att: AttentionItem = serde_json::from_value(attention_json).unwrap();

        assert_eq!(wf.status, WorkflowRunStatus::Completed);
        assert_eq!(wf.trigger_source, TriggerSource::Mcp);
        assert_eq!(att.source_type, AttentionSourceType::Train);
        assert!(!att.resolved);
    }

    // -----------------------------------------------------------------------
    // Violation severity coverage
    // -----------------------------------------------------------------------

    #[test]
    fn test_violation_severity_all() {
        let severities = [
            ("critical", ViolationSeverity::Critical),
            ("high", ViolationSeverity::High),
            ("medium", ViolationSeverity::Medium),
            ("low", ViolationSeverity::Low),
            ("info", ViolationSeverity::Info),
        ];
        for (json_str, expected) in &severities {
            let v: ViolationSeverity =
                serde_json::from_str(&format!("\"{}\"", json_str)).expect(json_str);
            assert_eq!(&v, expected);
        }
    }

    // -----------------------------------------------------------------------
    // Policy target types
    // -----------------------------------------------------------------------

    #[test]
    fn test_policy_target_types() {
        let types = [
            ("pr", PolicyTargetType::Pr),
            ("artifact", PolicyTargetType::Artifact),
            ("workflow-output", PolicyTargetType::WorkflowOutput),
        ];
        for (json_str, expected) in &types {
            let v: PolicyTargetType =
                serde_json::from_str(&format!("\"{}\"", json_str)).expect(json_str);
            assert_eq!(&v, expected);
        }
    }

    // -----------------------------------------------------------------------
    // Attention source types
    // -----------------------------------------------------------------------

    #[test]
    fn test_attention_source_types() {
        let types = [
            ("workflow", AttentionSourceType::Workflow),
            ("pr", AttentionSourceType::Pr),
            ("train", AttentionSourceType::Train),
            ("policy", AttentionSourceType::Policy),
            ("agent", AttentionSourceType::Agent),
        ];
        for (json_str, expected) in &types {
            let v: AttentionSourceType =
                serde_json::from_str(&format!("\"{}\"", json_str)).expect(json_str);
            assert_eq!(&v, expected);
        }
    }
}
