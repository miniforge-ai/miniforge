//! # miniforge-event-client
//!
//! Watches `~/.miniforge/events/` for new `.edn` event files, parses them
//! into typed [`ParsedEvent`] structs, and emits them to a
//! [`tokio::sync::mpsc`] channel.
//!
//! On startup, all existing event files are replayed in filename-sorted order
//! (chronological when files use timestamp prefixes) before live watching
//! begins.  Replayed events are tagged with [`ParsedEvent::is_replay`]`= true`
//! so consumers can distinguish history reconstruction from live data.
//!
//! ## Quick start
//!
//! ```rust,no_run
//! use event_client::{EventClient, EventClientConfig};
//!
//! #[tokio::main]
//! async fn main() -> anyhow::Result<()> {
//!     let config = EventClientConfig::default_events_dir()?;
//!     let (mut rx, _handle) = EventClient::new(config).start().await?;
//!
//!     while let Some(event) = rx.recv().await {
//!         println!(
//!             "[{}] {} | id={:?} | file={}",
//!             if event.is_replay { "replay" } else { "live  " },
//!             event.kind,
//!             event.id,
//!             event.source_file.display(),
//!         );
//!     }
//!     Ok(())
//! }
//! ```
//!
//! ## Module layout
//!
//! | Module | Purpose |
//! |--------|---------|
//! | [`events`] | Contract types: [`EventType`] enum + [`ParsedEvent`] struct |
//! | [`parser`] | EDN → [`ParsedEvent`] parsing |
//! | [`replay`] | Startup replay of existing event files |
//! | [`watcher`] | Live `notify`-based file-system watcher |
//! | [`client`] | High-level [`EventClient`] orchestrator |

pub mod client;
pub mod events;
pub mod parser;
pub mod replay;
pub mod watcher;

// Re-export the most commonly needed types at the crate root
pub use client::{EventClient, EventClientConfig, EventClientHandle};
pub use events::{EventType, ParsedEvent};
