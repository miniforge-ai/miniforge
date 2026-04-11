// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
// Licensed under the Apache License, Version 2.0

//! WorkflowRun — tracks the lifecycle of a single workflow execution.
//!
//! Mirrors the Malli schema `ai.miniforge.schema.supervisory/WorkflowRun`.
//! Required fields: workflow/id, workflow/key, workflow/status,
//! workflow/phase, workflow/started-at.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Possible states for a workflow run.
///
/// Maps to the Clojure enum: :pending :running :paused :completed :failed :cancelled
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum WorkflowStatus {
    Pending,
    Running,
    Paused,
    Completed,
    Failed,
    Cancelled,
}

/// Phases in the outer-loop SDLC cycle.
///
/// Maps to the Clojure enum: :plan :design :implement :verify :review :release :observe
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
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

/// Trigger sources for a workflow run.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum TriggerSource {
    Mcp,
    Cli,
    Api,
    Chain,
}

/// A supervisory workflow run.
///
/// Tracks the lifecycle of a single workflow execution.
/// Open map — optional fields may be absent in JSON.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WorkflowRun {
    /// Unique run identity (UUID).
    #[serde(rename = "workflow/id")]
    pub id: Uuid,

    /// Reference to the workflow spec (e.g. "feature-auth").
    #[serde(rename = "workflow/key")]
    pub key: String,

    /// Current execution status.
    #[serde(rename = "workflow/status")]
    pub status: WorkflowStatus,

    /// Current execution phase.
    #[serde(rename = "workflow/phase")]
    pub phase: WorkflowPhase,

    /// When the run started.
    #[serde(rename = "workflow/started-at")]
    pub started_at: DateTime<Utc>,

    /// Human-readable intent description (optional).
    #[serde(rename = "workflow/intent", default, skip_serializing_if = "Option::is_none")]
    pub intent: Option<String>,

    /// Last state change timestamp (optional).
    #[serde(rename = "workflow/updated-at", default, skip_serializing_if = "Option::is_none")]
    pub updated_at: Option<DateTime<Utc>>,

    /// How the workflow was triggered (optional).
    #[serde(rename = "workflow/trigger-source", default, skip_serializing_if = "Option::is_none")]
    pub trigger_source: Option<TriggerSource>,

    /// Correlation ID linking to related entities (optional).
    #[serde(rename = "workflow/correlation-id", default, skip_serializing_if = "Option::is_none")]
    pub correlation_id: Option<Uuid>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_workflow_status_serde() {
        let json = serde_json::to_string(&WorkflowStatus::Running).unwrap();
        assert_eq!(json, r#""running""#);

        let parsed: WorkflowStatus = serde_json::from_str(r#""failed""#).unwrap();
        assert_eq!(parsed, WorkflowStatus::Failed);
    }

    #[test]
    fn test_workflow_phase_serde() {
        let json = serde_json::to_string(&WorkflowPhase::Implement).unwrap();
        assert_eq!(json, r#""implement""#);
    }

    #[test]
    fn test_workflow_run_minimal() {
        let run = WorkflowRun {
            id: Uuid::nil(),
            key: "feature-auth".into(),
            status: WorkflowStatus::Running,
            phase: WorkflowPhase::Implement,
            started_at: DateTime::parse_from_rfc3339("2026-04-10T12:00:00Z")
                .unwrap()
                .with_timezone(&Utc),
            intent: None,
            updated_at: None,
            trigger_source: None,
            correlation_id: None,
        };

        let json = serde_json::to_string_pretty(&run).unwrap();
        let roundtrip: WorkflowRun = serde_json::from_str(&json).unwrap();
        assert_eq!(run, roundtrip);
    }
}
