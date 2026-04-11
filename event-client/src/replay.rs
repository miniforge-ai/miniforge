// Title: Miniforge.ai
// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
// Licensed under the Apache License, Version 2.0

//! Startup replay of recent events for durable state reconstruction.
//!
//! On startup, the client reads all existing `.edn` files in the events
//! directory (sorted by modification time, most recent first) and replays
//! them through the channel. This lets consumers rebuild their state from
//! the event log without requiring a separate database.

use std::fs;
use std::path::Path;
use std::time::{Duration, SystemTime};

use log::{debug, info, warn};
use tokio::sync::mpsc;

use crate::event::Event;
use crate::parser;

/// Configuration for event replay on startup.
#[derive(Debug, Clone)]
pub struct ReplayConfig {
    /// Maximum age of event files to replay.
    /// Files older than this are skipped.
    /// Default: 24 hours.
    pub max_age: Duration,

    /// Maximum number of events to replay across all files.
    /// Prevents memory blowup on large event directories.
    /// Default: 10_000.
    pub max_events: usize,

    /// Maximum number of workflow files to replay.
    /// Default: 100.
    pub max_files: usize,
}

impl Default for ReplayConfig {
    fn default() -> Self {
        Self {
            max_age: Duration::from_secs(24 * 60 * 60),
            max_events: 10_000,
            max_files: 100,
        }
    }
}

/// Result of a replay operation.
#[derive(Debug)]
pub struct ReplayResult {
    /// Number of events successfully replayed.
    pub events_replayed: usize,
    /// Number of files scanned.
    pub files_scanned: usize,
    /// Number of parse errors encountered (events skipped).
    pub parse_errors: usize,
    /// Number of files skipped due to age.
    pub files_skipped_age: usize,
}

/// Replay recent events from the events directory into the channel.
///
/// Events are sent in chronological order (oldest first within each file,
/// files sorted by modification time oldest-first). This ensures consumers
/// see events in approximately the same order they were originally emitted.
///
/// # Arguments
/// * `events_dir` — Path to `~/.miniforge/events/`
/// * `tx` — Sender half of the event channel
/// * `config` — Replay configuration
///
/// # Returns
/// A [`ReplayResult`] summarizing what was replayed.
pub async fn replay_recent_events(
    events_dir: &Path,
    tx: &mpsc::Sender<Event>,
    config: &ReplayConfig,
) -> ReplayResult {
    let mut result = ReplayResult {
        events_replayed: 0,
        files_scanned: 0,
        parse_errors: 0,
        files_skipped_age: 0,
    };

    if !events_dir.exists() {
        info!("Events directory does not exist, nothing to replay: {}", events_dir.display());
        return result;
    }

    // Collect and sort .edn files by modification time (oldest first)
    let mut edn_files = collect_edn_files(events_dir, config);

    // Sort by modification time, oldest first for chronological replay
    edn_files.sort_by_key(|(_, mtime)| *mtime);

    let now = SystemTime::now();

    for (path, mtime) in edn_files.iter().take(config.max_files) {
        // Skip files older than max_age
        if let Ok(age) = now.duration_since(*mtime) {
            if age > config.max_age {
                result.files_skipped_age += 1;
                continue;
            }
        }

        result.files_scanned += 1;

        let content = match fs::read_to_string(path) {
            Ok(c) => c,
            Err(e) => {
                warn!("Could not read {}: {}", path.display(), e);
                continue;
            }
        };

        let source = path.to_string_lossy().to_string();
        let parse_results = parser::parse_events(&content, Some(&source));

        for parse_result in parse_results {
            if result.events_replayed >= config.max_events {
                info!(
                    "Reached max replay events ({}), stopping",
                    config.max_events
                );
                return result;
            }

            match parse_result {
                Ok(event) => {
                    if tx.send(event).await.is_err() {
                        warn!("Channel closed during replay");
                        return result;
                    }
                    result.events_replayed += 1;
                }
                Err(e) => {
                    debug!("Skipping unparseable event in {}: {}", path.display(), e);
                    result.parse_errors += 1;
                }
            }
        }
    }

    info!(
        "Replay complete: {} events from {} files ({} errors, {} skipped)",
        result.events_replayed, result.files_scanned, result.parse_errors, result.files_skipped_age
    );

    result
}

/// Collect `.edn` files with their modification times.
fn collect_edn_files(
    events_dir: &Path,
    _config: &ReplayConfig,
) -> Vec<(std::path::PathBuf, SystemTime)> {
    let entries = match fs::read_dir(events_dir) {
        Ok(e) => e,
        Err(e) => {
            warn!("Could not read events dir: {}", e);
            return Vec::new();
        }
    };

    entries
        .flatten()
        .filter_map(|entry| {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) == Some("edn") && path.is_file() {
                let mtime = entry.metadata().ok()?.modified().ok()?;
                Some((path, mtime))
            } else {
                None
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use tempfile::TempDir;

    fn write_event_file(dir: &Path, name: &str, events: &[&str]) {
        let path = dir.join(name);
        let mut f = fs::File::create(path).unwrap();
        for event in events {
            writeln!(f, "{}", event).unwrap();
        }
    }

    fn sample_event(seq: i64, event_type: &str) -> String {
        format!(
            r#"{{:event/type :{}, :event/id #uuid "550e8400-e29b-41d4-a716-44665544{:04}", :event/timestamp #inst "2026-04-10T12:00:{:02}.000Z", :event/version "1.0.0", :event/sequence-number {}, :workflow/id #uuid "660e8400-e29b-41d4-a716-446655440000", :message "Event {seq}"}}"#,
            event_type, seq, seq, seq, seq = seq
        )
    }

    #[tokio::test]
    async fn replay_empty_directory() {
        let dir = TempDir::new().unwrap();
        let (tx, _rx) = mpsc::channel(100);
        let config = ReplayConfig::default();

        let result = replay_recent_events(dir.path(), &tx, &config).await;
        assert_eq!(result.events_replayed, 0);
        assert_eq!(result.files_scanned, 0);
    }

    #[tokio::test]
    async fn replay_nonexistent_directory() {
        let dir = Path::new("/tmp/miniforge-test-nonexistent-dir-12345");
        let (tx, _rx) = mpsc::channel(100);
        let config = ReplayConfig::default();

        let result = replay_recent_events(dir, &tx, &config).await;
        assert_eq!(result.events_replayed, 0);
    }

    #[tokio::test]
    async fn replay_single_file() {
        let dir = TempDir::new().unwrap();
        let events = vec![
            sample_event(0, "workflow/started"),
            sample_event(1, "agent/started"),
            sample_event(2, "agent/completed"),
        ];
        let event_strs: Vec<&str> = events.iter().map(|s| s.as_str()).collect();
        write_event_file(dir.path(), "workflow-1.edn", &event_strs);

        let (tx, mut rx) = mpsc::channel(100);
        let config = ReplayConfig::default();

        let result = replay_recent_events(dir.path(), &tx, &config).await;
        assert_eq!(result.events_replayed, 3);
        assert_eq!(result.files_scanned, 1);
        assert_eq!(result.parse_errors, 0);

        // Verify events come through in order
        let e0 = rx.recv().await.unwrap();
        assert_eq!(e0.event_type, crate::event::EventType::WorkflowStarted);
        let e1 = rx.recv().await.unwrap();
        assert_eq!(e1.event_type, crate::event::EventType::AgentStarted);
        let e2 = rx.recv().await.unwrap();
        assert_eq!(e2.event_type, crate::event::EventType::AgentCompleted);
    }

    #[tokio::test]
    async fn replay_respects_max_events() {
        let dir = TempDir::new().unwrap();
        let events: Vec<String> = (0..20)
            .map(|i| sample_event(i, "workflow/started"))
            .collect();
        let event_strs: Vec<&str> = events.iter().map(|s| s.as_str()).collect();
        write_event_file(dir.path(), "big-workflow.edn", &event_strs);

        let (tx, mut rx) = mpsc::channel(100);
        let config = ReplayConfig {
            max_events: 5,
            ..Default::default()
        };

        let result = replay_recent_events(dir.path(), &tx, &config).await;
        assert_eq!(result.events_replayed, 5);

        // Drain channel
        let mut count = 0;
        while rx.try_recv().is_ok() {
            count += 1;
        }
        assert_eq!(count, 5);
    }

    #[tokio::test]
    async fn replay_skips_non_edn_files() {
        let dir = TempDir::new().unwrap();
        fs::write(dir.path().join("readme.md"), "# Hello").unwrap();
        fs::write(dir.path().join(".hidden"), "secret").unwrap();

        let events = vec![sample_event(0, "workflow/started")];
        let event_strs: Vec<&str> = events.iter().map(|s| s.as_str()).collect();
        write_event_file(dir.path(), "workflow.edn", &event_strs);

        let (tx, _rx) = mpsc::channel(100);
        let config = ReplayConfig::default();

        let result = replay_recent_events(dir.path(), &tx, &config).await;
        assert_eq!(result.events_replayed, 1);
        assert_eq!(result.files_scanned, 1);
    }
}
