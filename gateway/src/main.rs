use anyhow::Result;
use clap::{Parser, Subcommand};
use pinkcollab_gateway::{
    api::{self, App},
    config::{self, Config},
    events::Bus,
    model::Host,
    omp,
    session::Registry,
    storage::Store,
    workspace::Browser,
};
use std::{path::PathBuf, sync::Arc, time::Duration};

mod admin;

#[cfg(windows)]
mod windows;

#[derive(Parser)]
#[command(version, about = "Remote control plane for Oh My Pi")]
struct Cli {
    #[arg(long,global=true,default_value_os_t=config::data_dir())]
    data_dir: PathBuf,
    #[command(subcommand)]
    command: Option<Commands>,
}
#[derive(Subcommand)]
enum Commands {
    /// One-time bootstrap: write config.yaml with the allowed workspace roots.
    Init {
        /// Allowed root directory; repeat the flag for more than one root.
        #[arg(long, required = true, num_args = 1..)]
        workspace: Vec<PathBuf>,
    },
    /// Run the Gateway. This is what a bare invocation does.
    Serve,
    /// Run under the Windows Service Control Manager.
    #[cfg(windows)]
    Service,
    /// Print a single-use pairing code for one phone.
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
    /// Preflight the configuration, roots, OMP, database and listen port.
    Status,
    /// Publish the loopback Gateway on the internet with Tailscale Funnel.
    SetupFunnel {
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
}
#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    #[cfg(windows)]
    if matches!(cli.command, Some(Commands::Service)) {
        return windows::dispatch(cli.data_dir);
    }
    match cli.command.unwrap_or(Commands::Serve) {
        Commands::Init { workspace } => admin::init(&cli.data_dir, &workspace),
        Commands::Pair { url, qr } => admin::pair(&cli.data_dir, url, qr),
        Commands::Clients => admin::clients(&cli.data_dir),
        Commands::Status => admin::status(&cli.data_dir).await,
        Commands::Revoke { client } => admin::revoke(&cli.data_dir, &client),
        Commands::SetupFunnel {
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
            serve_until(config, Arc::new(Store::open(&cli.data_dir)?), shutdown()).await
        }
        #[cfg(windows)]
        Commands::Service => unreachable!(),
    }
}
async fn serve_until(
    config: Config,
    store: Arc<Store>,
    stop: impl Future<Output = ()>,
) -> Result<()> {
    let browser = Arc::new(Browser::new(&config.workspaces)?);
    let host_id = store.host_id()?;
    let bus = Arc::new(Bus::default());
    let registry = Registry::new(
        store.clone(),
        bus.clone(),
        browser.clone(),
        host_id.clone(),
        config.omp.clone(),
        config.omp_args.clone(),
        config.max_sessions,
    )?;
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
        registry: registry.clone(),
        bus: bus.clone(),
    });
    let handle = axum_server::Handle::new();
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
            registry.close().await;
            result??;
        },
        _ = stop => {
            bus.publish("gateway.shutdown", serde_json::json!({}));
            handle.graceful_shutdown(Some(Duration::from_secs(10)));
            registry.close().await;
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
