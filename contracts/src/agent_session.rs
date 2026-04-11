// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
// Licensed under the Apache License, Version 2.0

//! AgentSession — tracks a registered agent in the control plane.
//!
//! Mirrors the agent records from
//! `ai.miniforge.control-plane.registry/register-agent!`.
//! Required fields: agent/id, agent/vendor, agent/external-id,
//! agent/name, agent/status, agent/registered-at, agent/last-heartbeat.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

/// Possible states for an agent session.
///
/// Status transitions: :idle → :starting → :executing → (:blocked | :completed | :failed)
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
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

/// A registered agent session in the control plane.
///
/// Tracks lifecycle, heartbeat, and metadata for any vendor's agent.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AgentSession {
    /// Control-plane-assigned UUID.
    #[serde(rename = "agent/id")]
    pub id: Uuid,

    /// Adapter key (e.g. "claude-code").
    #[serde(rename = "agent/vendor")]
    pub vendor: String,

    /// Vendor-specific identifier.
    #[serde(rename = "agent/external-id")]
    pub external_id: String,

    /// Human-readable name.
    #[serde(rename = "agent/name")]
    pub name: String,

    /// Current execution status.
    #[serde(rename = "agent/status")]
    pub status: AgentStatus,

    /// When the agent was registered.
    #[serde(rename = "agent/registered-at")]
    pub registered_at: DateTime<Utc>,

    /// Last heartbeat timestamp.
    #[serde(rename = "agent/last-heartbeat")]
    pub last_heartbeat: DateTime<Utc>,

    /// Capability keywords (optional).
    #[serde(rename = "agent/capabilities", default, skip_serializing_if = "Option::is_none")]
    pub capabilities: Option<Vec<String>>,

    /// Expected heartbeat interval in milliseconds (optional, default 30000).
    #[serde(rename = "agent/heartbeat-interval-ms", default, skip_serializing_if = "Option::is_none")]
    pub heartbeat_interval_ms: Option<u64>,

    /// Vendor-specific opaque data (optional).
    #[serde(rename = "agent/metadata", default, skip_serializing_if = "Option::is_none")]
    pub metadata: Option<HashMap<String, serde_json::Value>>,

    /// User-defined grouping tags (optional).
    #[serde(rename = "agent/tags", default, skip_serializing_if = "Option::is_none")]
    pub tags: Option<Vec<String>>,

    /// Current task description from heartbeat (optional).
    #[serde(rename = "agent/task", default, skip_serializing_if = "Option::is_none")]
    pub task: Option<String>,

    /// Cost/token metrics (optional).
    #[serde(rename = "agent/metrics", default, skip_serializing_if = "Option::is_none")]
    pub metrics: Option<HashMap<String, serde_json::Value>>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_agent_status_serde() {
        let json = serde_json::to_string(&AgentStatus::Executing).unwrap();
        assert_eq!(json, r#""executing""#);

        let parsed: AgentStatus = serde_json::from_str(r#""idle""#).unwrap();
        assert_eq!(parsed, AgentStatus::Idle);
    }

    #[test]
    fn test_agent_session_roundtrip() {
        let session = AgentSession {
            id: Uuid::nil(),
            vendor: "claude-code".into(),
            external_id: "session-abc".into(),
            name: "PR Review Agent".into(),
            status: AgentStatus::Executing,
            registered_at: DateTime::parse_from_rfc3339("2026-04-10T10:00:00Z")
                .unwrap()
                .with_timezone(&Utc),
            last_heartbeat: DateTime::parse_from_rfc3339("2026-04-10T12:00:00Z")
                .unwrap()
                .with_timezone(&Utc),
            capabilities: None,
            heartbeat_interval_ms: None,
            metadata: None,
            tags: None,
            task: None,
            metrics: None,
        };

        let json = serde_json::to_string_pretty(&session).unwrap();
        let roundtrip: AgentSession = serde_json::from_str(&json).unwrap();
        assert_eq!(session, roundtrip);
    }
}
