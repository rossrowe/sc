import com.saucelabs.kgp.{KgpClient, ProxyServer}

object TunnelClient {

  def main(args: Array[String]) {
    // Print usage if no argument is specified.
    if (args.length < 3 || args.length > 4) {
      System.err.println(
        "Usage: " + TunnelClient.getClass.getSimpleName +
          " <host> <port> <forward port>")
      return
    }

    // Parse options.
    val host = args(0)
    val port = args(1).toInt
    val forwardPort = args(2).toInt

    val client = new KgpClient(host, port, forwardPort, "{\"username\": \"sah\", \"access_key\": \"1196e57a-b7b6-4290-9bf7-9424b9ebc0d0\"}")
    client.start()
    client.connect()
    val proxyServer = new ProxyServer(client, 4445)
    proxyServer.serve()
    // Wait until the connection is closed or the connection attempt fails.
    //future.getChannel.getCloseFuture.awaitUninterruptibly

    // Shut down thread pools to exit.
    //bootstrap.releaseExternalResources
  }
}
