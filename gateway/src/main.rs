use anyhow::Result;
use clap::{Parser, Subcommand};
use pinkcollab_gateway::{
    api::{self, App},
    config::{self, Config},
    events::Bus,
    model::Host,
    omp,
    storage::Store,
    workspace::Browser,
};
use std::{path::PathBuf, sync::Arc, time::Duration};

mod admin;
mod health;
mod remote;
mod service;
mod setup;
mod setup_connect;
mod setup_control;

#[cfg(windows)]
mod windows;

#[derive(Parser)]
#[command(
    name = "pinkcollab",
    version,
    about = "Remote control plane for Oh My Pi"
)]
struct Cli {
    #[arg(long,global=true,default_value_os_t=config::data_dir())]
    data_dir: PathBuf,
    #[command(subcommand)]
    command: Option<Commands>,
}
#[derive(Subcommand)]
enum Commands {
    /// Set up or update PinkCollab.
    #[command(display_order = 0)]
    Setup {
        #[arg(long)]
        workspace: Vec<PathBuf>,
        #[arg(long)]
        non_interactive: bool,
        #[arg(long, value_enum, default_value_t = setup::Transport::Tailscale)]
        transport: setup::Transport,
        /// Explicit HTTPS Gateway root URL for an externally managed reverse proxy.
        #[arg(long)]
        public_url: Option<String>,
    },
    /// Manage the background Gateway.
    #[command(display_order = 3)]
    Service {
        #[command(subcommand)]
        action: service::Action,
    },
    /// Diagnose installation and connection problems.
    #[command(display_order = 4)]
    Doctor,
    /// Advanced/manual bootstrap: write config.yaml with the allowed workspace roots.
    Init {
        /// Allowed root directory; repeat the flag for more than one root.
        #[arg(long, required = true, num_args = 1..)]
        workspace: Vec<PathBuf>,
    },
    /// Run the Gateway in the foreground (advanced/manual).
    Serve,
    #[cfg(windows)]
    #[command(hide = true)]
    BackgroundRun,
    #[cfg(windows)]
    #[command(hide = true)]
    BackgroundStart,
    /// Pair another phone.
    #[command(display_order = 2)]
    Pair {
        /// Gateway root URL the phone dials; defaults to the configured public_url.
        #[arg(long)]
        url: Option<String>,
        /// Also write the code to this PNG file.
        #[arg(long)]
        qr: Option<PathBuf>,
    },
    /// List the devices paired with this Gateway.
    Clients,
    /// Show PinkCollab status.
    #[command(display_order = 1)]
    Status,
    /// Advanced/manual: publish the Gateway with Tailscale Funnel. Does not start the Gateway.
    Funnel {
        /// Public HTTPS port offered by Funnel: 443, 8443 or 10000.
        #[arg(long, default_value_t = 443)]
        https: u16,
        /// Print the plan without touching Tailscale or config.yaml.
        #[arg(long)]
        dry_run: bool,
        /// Path to the tailscale executable, when it is not on PATH.
        #[arg(long)]
        tailscale: Option<PathBuf>,
        /// Print a pairing code as soon as Funnel publishes the Gateway.
        #[arg(long)]
        pair: bool,
    },
    /// Delete a paired device's credential on the host.
    Revoke {
        #[arg(long)]
        client: String,
    },
    /// List conservative runtime leases left by processes that were not confirmed exited.
    Leases,
    /// Clear one lease after verifying its OMP process and descendants have exited.
    ClearLease {
        #[arg(long)]
        session: String,
        #[arg(long)]
        generation: String,
        #[arg(long)]
        verified_exited: bool,
    },
}
#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    #[cfg(windows)]
    if matches!(cli.command, Some(Commands::BackgroundRun)) {
        return windows::run(cli.data_dir).await;
    }
    let Some(command) = cli.command else {
        return admin::default_entry(&cli.data_dir).await;
    };
    match command {
        Commands::Setup {
            workspace,
            non_interactive,
            transport,
            public_url,
        } => match setup::run(
            &cli.data_dir,
            &workspace,
            non_interactive,
            transport,
            public_url.as_deref(),
        )
        .await
        {
            Ok(()) => Ok(()),
            Err(error) => {
                eprintln!("{error:#}\n\nFor details, run pinkcollab doctor.");
                std::process::exit(1);
            }
        },
        Commands::Service { action } => service::execute(&cli.data_dir, action),
        Commands::Doctor => admin::doctor(&cli.data_dir).await,
        Commands::Init { workspace } => admin::init(&cli.data_dir, &workspace),
        Commands::Pair { url, qr } => admin::pair(&cli.data_dir, url, qr),
        Commands::Clients => admin::clients(&cli.data_dir),
        Commands::Status => admin::status(&cli.data_dir).await,
        Commands::Revoke { client } => admin::revoke(&cli.data_dir, &client),
        Commands::Leases => admin::leases(&cli.data_dir),
        Commands::ClearLease {
            session,
            generation,
            verified_exited,
        } => admin::clear_lease(&cli.data_dir, &session, &generation, verified_exited),
        Commands::Funnel {
            https,
            dry_run,
            tailscale,
            pair,
        } => admin::setup_funnel(
            &cli.data_dir,
            pinkcollab_gateway::funnel::Options {
                https_port: https,
                dry_run,
                binary: tailscale.as_deref(),
            },
            pair,
        ),
        Commands::Serve => {
            let config = Config::load(&cli.data_dir)?;
            serve_until(
                config,
                Arc::new(Store::open(&cli.data_dir)?),
                shutdown(),
                axum_server::Handle::new(),
            )
            .await
        }
        #[cfg(windows)]
        Commands::BackgroundRun => unreachable!(),
        #[cfg(windows)]
        Commands::BackgroundStart => service::execute(&cli.data_dir, service::Action::Start),
    }
}
async fn serve_until(
    config: Config,
    store: Arc<Store>,
    stop: impl Future<Output = ()>,
    handle: axum_server::Handle,
) -> Result<()> {
    let browser = Arc::new(Browser::new(&config.workspaces)?);
    let host_id = store.host_id()?;
    let bus = Arc::new(Bus::default());
    store.recover_operations()?;
    let sessions = pinkcollab_gateway::runtime::SessionDirectory::new(
        store.clone(),
        browser.clone(),
        bus.clone(),
        config.omp.clone(),
        config.omp_args.clone(),
        config.max_sessions,
    );
    let version = omp::version(&config.omp).await;
    let app = api::router(App {
        host: Host {
            id: host_id,
            name: config.name.clone(),
            os: std::env::consts::OS.into(),
            status: "online".into(),
            omp_version: version,
            gateway_version: env!("CARGO_PKG_VERSION").into(),
        },
        store,
        browser,
        bus: bus.clone(),
        sessions: sessions.clone(),
    });
    let server_handle = handle.clone();
    println!(
        "PinkCollab {} listening on {}; host {}",
        env!("CARGO_PKG_VERSION"),
        config.listen,
        config.name
    );
    if config.public_url.is_empty() {
        println!("public_url is empty; pair with --url https://<host>");
    } else {
        println!("Public URL {} (TLS terminated upstream)", config.public_url);
    }
    let task = tokio::spawn(async move {
        axum_server::bind(config.listen)
            .handle(server_handle)
            .serve(app.into_make_service())
            .await?;
        Ok::<(), anyhow::Error>(())
    });
    tokio::pin!(task);
    tokio::select! {
        result = &mut task => {
            sessions.close().await;
            result??;
        },
        _ = stop => {
            bus.disconnect();
            handle.graceful_shutdown(Some(Duration::from_secs(10)));
            sessions.close().await;
            task.await??;
        },
    }
    Ok(())
}
async fn shutdown() {
    #[cfg(unix)]
    {
        let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("install SIGTERM handler");
        tokio::select! {_=tokio::signal::ctrl_c()=>{},_=term.recv()=>{}}
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}

#[cfg(test)]
mod cli_tests {
    use super::*;

    #[test]
    fn setup_transport_defaults_to_tailscale_and_accepts_explicit_choices() {
        for args in [
            vec!["pinkcollab", "setup"],
            vec!["pinkcollab", "setup", "--transport", "tailscale"],
        ] {
            let cli = Cli::try_parse_from(args).unwrap();
            assert!(matches!(
                cli.command,
                Some(Commands::Setup {
                    transport: setup::Transport::Tailscale,
                    ..
                })
            ));
        }
        let cli = Cli::try_parse_from([
            "pinkcollab",
            "setup",
            "--transport",
            "external",
            "--public-url",
            "https://pink.example.com",
        ])
        .unwrap();
        match cli.command {
            Some(Commands::Setup {
                transport: setup::Transport::External,
                public_url,
                ..
            }) => assert_eq!(public_url.as_deref(), Some("https://pink.example.com")),
            _ => panic!("expected external setup"),
        }
        assert!(Cli::try_parse_from(["pinkcollab", "setup", "--transport", "unknown"]).is_err());
    }
}
