//! EDN → [`ParsedEvent`] parser.
//!
//! Expects each file to contain a single EDN map whose keys follow Clojure's
//! namespace-qualified keyword convention:
//!
//! ```edn
//! {:event/id        "abc-123"
//!  :event/type      :task/started
//!  :event/timestamp #inst "2026-04-11T10:00:00.000-00:00"
//!  :event/source    "planner"
//!  :task/id         "task-456"
//!  :message         "Task execution started"}
//! ```

use anyhow::{anyhow, Context, Result};
use edn_rs::{parse_edn, Edn};
use std::path::PathBuf;
use tracing::{debug, warn};

use crate::events::{EventType, ParsedEvent};

// ─── Public entry point ───────────────────────────────────────────────────────

/// Parse the EDN `content` of a single event file into a [`ParsedEvent`].
///
/// Returns an error if:
/// * `content` is not valid EDN.
/// * The top-level EDN value is not a map.
pub fn parse_event_file(content: &str, source_file: PathBuf, is_replay: bool) -> Result<ParsedEvent> {
    let edn = parse_edn(content)
        .with_context(|| format!("EDN parse error in {source_file:?}"))?;

    extract_event(&edn, content.to_owned(), source_file, is_replay)
}

// ─── Extraction helpers ───────────────────────────────────────────────────────

fn extract_event(
    edn: &Edn,
    raw_edn: String,
    source_file: PathBuf,
    is_replay: bool,
) -> Result<ParsedEvent> {
    match edn {
        Edn::Map(_) => {
            let kind      = extract_event_type(edn);
            let id        = extract_str(edn, ":event/id");
            let timestamp = extract_timestamp(edn);
            let source    = extract_str(edn, ":event/source");
            let task_id   = extract_str(edn, ":task/id");
            let agent_id  = extract_str(edn, ":agent/id");
            let workflow_id = extract_str(edn, ":workflow/id");
            let message   = extract_str(edn, ":event/message")
                .or_else(|| extract_str(edn, ":message"));

            debug!(
                ?source_file,
                kind = kind.as_keyword(),
                id   = ?id,
                "parsed event"
            );

            Ok(ParsedEvent {
                id,
                kind,
                timestamp,
                source,
                task_id,
                agent_id,
                workflow_id,
                message,
                raw_edn,
                source_file,
                is_replay,
            })
        }
        other => {
            warn!(?source_file, "event file is not an EDN map");
            Err(anyhow!(
                "expected EDN map in {source_file:?}, got {:?}",
                std::mem::discriminant(other)
            ))
        }
    }
}

/// Extract `:event/type` as an [`EventType`].
fn extract_event_type(edn: &Edn) -> EventType {
    extract_keyword(edn, ":event/type")
        .map(|kw| EventType::from_keyword(&kw))
        .unwrap_or_else(|| EventType::Unknown(String::new()))
}

/// Extract a scalar field as a `String`.
/// Handles Str, Key, Symbol, and Int values gracefully.
fn extract_str(edn: &Edn, key: &str) -> Option<String> {
    let val = map_get(edn, key)?;
    match val {
        Edn::Str(s)    => Some(s.clone()),
        Edn::Key(k)    => Some(k.clone()),
        Edn::Symbol(s) => Some(s.clone()),
        Edn::Int(i)    => Some(i.to_string()),
        _              => None,
    }
}

/// Extract a keyword field (`:some/keyword`) as a String.
/// Falls back to Str and Symbol so loose event writers still work.
fn extract_keyword(edn: &Edn, key: &str) -> Option<String> {
    let val = map_get(edn, key)?;
    match val {
        Edn::Key(k)    => Some(k.clone()),
        Edn::Str(s)    => Some(s.clone()),
        Edn::Symbol(s) => Some(s.clone()),
        _              => None,
    }
}

/// Extract `:event/timestamp`, handling both plain strings and `#inst` tagged literals.
fn extract_timestamp(edn: &Edn) -> Option<String> {
    let val = map_get(edn, ":event/timestamp")?;
    match val {
        Edn::Str(s) => Some(s.clone()),
        Edn::Key(k) => Some(k.clone()),
        // #inst "2026-04-11T10:00:00.000-00:00" → Tagged("inst", Str("…"))
        Edn::Tagged(tag, inner) if tag == "inst" => {
            if let Edn::Str(s) = inner.as_ref() {
                Some(s.clone())
            } else {
                None
            }
        }
        _ => None,
    }
}

/// Retrieve a value from an `Edn::Map` by keyword key (e.g. `":event/id"`).
fn map_get<'a>(edn: &'a Edn, key: &str) -> Option<&'a Edn> {
    match edn {
        Edn::Map(map) => map.get(key),
        _             => None,
    }
}

// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::events::EventType;

    fn path(name: &str) -> PathBuf {
        PathBuf::from(name)
    }

    // ── Happy-path parsing ────────────────────────────────────────────────────

    #[test]
    fn parses_task_started_event() {
        let edn = r#"{:event/id "abc-123"
                      :event/type :task/started
                      :event/timestamp "2026-04-11T10:00:00Z"
                      :event/source "planner"
                      :task/id "task-456"
                      :message "started"}"#;
        let ev = parse_event_file(edn, path("task.edn"), false).unwrap();
        assert_eq!(ev.id.as_deref(),        Some("abc-123"));
        assert_eq!(ev.kind,                 EventType::TaskStarted);
        assert_eq!(ev.timestamp.as_deref(), Some("2026-04-11T10:00:00Z"));
        assert_eq!(ev.source.as_deref(),    Some("planner"));
        assert_eq!(ev.task_id.as_deref(),   Some("task-456"));
        assert_eq!(ev.message.as_deref(),   Some("started"));
        assert!(!ev.is_replay);
    }

    #[test]
    fn parses_agent_completed_event_as_replay() {
        let edn = r#"{:event/type :agent/completed
                      :agent/id "agent-789"
                      :event/id "evt-001"}"#;
        let ev = parse_event_file(edn, path("agent.edn"), true).unwrap();
        assert_eq!(ev.kind,                EventType::AgentCompleted);
        assert_eq!(ev.agent_id.as_deref(), Some("agent-789"));
        assert!(ev.is_replay);
    }

    #[test]
    fn parses_workflow_event_with_workflow_id() {
        let edn = r#"{:event/type :workflow/started
                      :workflow/id "wf-001"
                      :event/id "e-wf"}"#;
        let ev = parse_event_file(edn, path("wf.edn"), false).unwrap();
        assert_eq!(ev.kind,                   EventType::WorkflowStarted);
        assert_eq!(ev.workflow_id.as_deref(), Some("wf-001"));
    }

    #[test]
    fn inst_tagged_timestamp_is_extracted() {
        let edn = r#"{:event/type :task/completed
                      :event/id "e1"
                      :event/timestamp #inst "2026-04-11T10:00:00.000-00:00"}"#;
        let ev = parse_event_file(edn, path("inst.edn"), false).unwrap();
        assert_eq!(ev.timestamp.as_deref(), Some("2026-04-11T10:00:00.000-00:00"));
    }

    #[test]
    fn falls_back_to_message_key_without_namespace() {
        let edn = r#"{:event/type :task/failed :message "oh no"}"#;
        let ev = parse_event_file(edn, path("msg.edn"), false).unwrap();
        assert_eq!(ev.message.as_deref(), Some("oh no"));
    }

    #[test]
    fn unknown_event_type_preserved() {
        let edn = r#"{:event/type :custom/something :event/id "id-1"}"#;
        let ev = parse_event_file(edn, path("custom.edn"), false).unwrap();
        assert!(
            matches!(&ev.kind, EventType::Unknown(kw) if kw == "custom/something"),
            "expected Unknown(\"custom/something\"), got {:?}",
            ev.kind
        );
    }

    #[test]
    fn raw_edn_preserved_verbatim() {
        let edn = "{:event/type :task/started}";
        let ev = parse_event_file(edn, path("raw.edn"), false).unwrap();
        assert_eq!(ev.raw_edn, edn);
    }

    // ── Error paths ───────────────────────────────────────────────────────────

    #[test]
    fn invalid_edn_returns_error() {
        let result = parse_event_file("not valid edn {{{", path("bad.edn"), false);
        assert!(result.is_err(), "expected parse error for invalid EDN");
    }

    #[test]
    fn non_map_edn_returns_error() {
        let result = parse_event_file("[:task/started :agent/completed]", path("vec.edn"), false);
        assert!(result.is_err(), "expected error for non-map EDN");
    }
}
