use std::{net::SocketAddr, time::Duration};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
/// A bounded, loopback-only readiness probe with no credentials or private response data.
pub async fn healthy(address: SocketAddr) -> bool {
    if !address.ip().is_loopback() {
        return false;
    }
    tokio::time::timeout(Duration::from_secs(1), async {
        let mut socket = tokio::net::TcpStream::connect(address).await?;
        socket
            .write_all(b"GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            .await?;
        let mut bytes = Vec::new();
        socket.take(4096).read_to_end(&mut bytes).await?;
        let response = String::from_utf8_lossy(&bytes);
        Ok::<bool, std::io::Error>(
            response.starts_with("HTTP/1.1 200 ")
                && response
                    .split_once("\r\n\r\n")
                    .is_some_and(|(_, body)| body == "pinkcollab:ok"),
        )
    })
    .await
    .ok()
    .and_then(Result::ok)
    .unwrap_or(false)
}
pub async fn wait(address: SocketAddr) -> anyhow::Result<()> {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
    while tokio::time::Instant::now() < deadline {
        if healthy(address).await {
            return Ok(());
        }
        tokio::time::sleep(Duration::from_millis(250)).await;
    }
    anyhow::bail!(
        "Gateway could not start. Run pinkcollab doctor to inspect the background service."
    )
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn distinguishes_gateway_from_an_occupied_port() {
        for (body, expected) in [("pinkcollab:ok", true), ("another server", false)] {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let task = tokio::spawn(async move {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut request = [0; 1024];
                assert!(socket.read(&mut request).await.unwrap() > 0);
                socket
                    .write_all(
                        format!("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n{body}").as_bytes(),
                    )
                    .await
                    .unwrap();
            });
            assert_eq!(healthy(address).await, expected);
            task.await.unwrap();
            assert!(!healthy(address).await);
        }
    }
}
