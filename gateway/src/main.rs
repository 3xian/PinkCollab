use anyhow::{Context, Result, ensure};
use clap::{Parser, Subcommand};
use pinkcollab_gateway::{
    api::{self, App},
    config::{self, Config},
    events::Bus,
    funnel,
    model::Host,
    session::Registry,
    storage::{self, Store},
    workspace::Browser,
};
use std::{path::PathBuf, sync::Arc, time::Duration};

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
    Init {
        #[arg(long)]
        workspace: PathBuf,
    },
    Serve,
    /// Run under the Windows Service Control Manager.
    #[cfg(windows)]
    Service,
    Pair {
        #[arg(long)]
        url: Option<String>,
        #[arg(long, default_value = "pairing.png")]
        qr: PathBuf,
    },
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
    },
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
    if let Some(Commands::Init { workspace }) = &cli.command {
        let root = workspace.canonicalize().context("workspace unavailable")?;
        Browser::new(std::slice::from_ref(&root))?;
        storage::private_dir(&cli.data_dir)?;
        let path = cli.data_dir.join("config.yaml");
        ensure!(
            !path.exists(),
            "config.yaml already exists; edit it to add workspaces"
        );
        let config = Config {
            workspaces: vec![PathBuf::from(pinkcollab_gateway::workspace::display(&root))],
            ..Config::default()
        };
        storage::private_file(&path, serde_yaml::to_string(&config)?.as_bytes())?;
        println!("Configuration created: {}", path.display());
        return Ok(());
    }
    let config = Config::load(&cli.data_dir)?;
    let store = Arc::new(Store::open(&cli.data_dir)?);
    match cli.command.unwrap_or(Commands::Serve) {
        Commands::Pair { url, qr } => {
            let base = url.unwrap_or(config.public_url);
            let parsed = config::root_url(&base).context("pair requires --url https://<host>")?;
            ensure!(
                parsed.scheme() == "https"
                    || (parsed.scheme() == "http"
                        && ["127.0.0.1", "localhost", "10.0.2.2"]
                            .contains(&parsed.host_str().unwrap_or_default())),
                "pair URL requires HTTPS; HTTP is for loopback/emulator development only"
            );
            let token = store.new_pairing()?;
            let payload =
                serde_json::json!({"version":1,"url":base.trim_end_matches('/'),"token":token})
                    .to_string();
            let code = qrcode::QrCode::new(payload.as_bytes())?;
            let image = code
                .render::<image::Luma<u8>>()
                .min_dimensions(384, 384)
                .build();
            let mut png = std::io::Cursor::new(Vec::new());
            image.write_to(&mut png, image::ImageFormat::Png)?;
            storage::private_file(&qr, &png.into_inner())?;
            println!(
                "{payload}\nPairing QR: {} (single use, expires in 5 minutes)",
                qr.display()
            );
        }
        Commands::SetupFunnel {
            https,
            dry_run,
            tailscale,
        } => funnel::setup(
            &cli.data_dir,
            &config,
            funnel::Options {
                https_port: https,
                dry_run,
                binary: tailscale.as_deref(),
            },
        )?,
        Commands::Revoke { client } => store.revoke(&client)?,
        Commands::Serve => serve(config, store).await?,
        #[cfg(windows)]
        Commands::Service => unreachable!(),
        Commands::Init { .. } => unreachable!(),
    }
    Ok(())
}
async fn serve(config: Config, store: Arc<Store>) -> Result<()> {
    serve_until(config, store, shutdown()).await
}
async fn serve_until(
    config: Config,
    store: Arc<Store>,
    stop: impl std::future::Future<Output = ()>,
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
    let omp_version = tokio::time::timeout(
        Duration::from_secs(5),
        tokio::process::Command::new(&config.omp)
            .kill_on_drop(true)
            .arg("--version")
            .output(),
    )
    .await
    .ok()
    .and_then(Result::ok)
    .filter(|v| v.status.success())
    .map(|v| String::from_utf8_lossy(&v.stdout).trim().to_owned())
    .unwrap_or_else(|| "unavailable".into());
    let app = api::router(App {
        host: Host {
            id: host_id,
            name: config.name.clone(),
            os: std::env::consts::OS.into(),
            status: "online".into(),
            omp_version,
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
