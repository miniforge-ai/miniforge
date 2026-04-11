//! Standalone binary entry point for the miniforge event client.
//!
//! Prints each event to stdout as it arrives, prefixed with `[replay]` or
//! `[live  ]`.  Intended for manual inspection and smoke-testing; production
//! code should import the library crate instead.
//!
//! ## Usage
//!
//! ```text
//! # Watch the default ~/.miniforge/events/ directory
//! cargo run --bin event-client
//!
//! # Override the events directory via environment variable (optional)
//! MINIFORGE_EVENTS_DIR=/tmp/events cargo run --bin event-client
//! ```
//!
//! ## Log levels
//!
//! Set `RUST_LOG=event_client=debug` for verbose output.

use anyhow::Result;
use event_client::{EventClient, EventClientConfig};
use std::path::PathBuf;
use tracing::info;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<()> {
    // Initialise structured logging.
    // RUST_LOG=event_client=debug  →  verbose per-file tracing
    // RUST_LOG=info                →  startup / replay summary only
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| EnvFilter::new("event_client=info")),
        )
        .init();

    // Allow the events directory to be overridden by an env var for testing
    let config = match std::env::var("MINIFORGE_EVENTS_DIR").ok() {
        Some(dir) => {
            let path = PathBuf::from(&dir);
            info!(dir = %path.display(), "using MINIFORGE_EVENTS_DIR override");
            EventClientConfig {
                events_dir: path,
                channel_capacity: 1024,
            }
        }
        None => EventClientConfig::default_events_dir()?,
    };

    info!(
        dir      = %config.events_dir.display(),
        capacity = config.channel_capacity,
        "miniforge event client starting"
    );

    let (mut rx, _handle) = EventClient::new(config).start().await?;

    while let Some(event) = rx.recv().await {
        let tag = if event.is_replay { "[replay]" } else { "[live  ]" };
        let file = event
            .source_file
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("?");

        println!(
            "{tag} {kind:<30} id={id:<36} ts={ts:<30} file={file}",
            kind = event.kind.as_keyword(),
            id   = event.id.as_deref().unwrap_or("(none)"),
            ts   = event.timestamp.as_deref().unwrap_or("(none)"),
        );
    }

    info!("event channel closed — exiting");
    Ok(())
}
