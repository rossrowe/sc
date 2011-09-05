/*
 * Adapted from the Selenium project under the Apache License 2.0
 *
 * Portions Copyright 2011 Sauce Labs, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.saucelabs.sauceconnect.proxy

import java.security.GeneralSecurityException

import javax.net.ssl._

object TrustEverythingSSLTrustManager {
  /**
   * Initialize an SSLSocketFactory that will trust all SSL certificates this is suitable for passing to
   * HttpsURLConnection, either to its instance method setSSLSocketFactory, or to its static method
   * setDefaultSSLSocketFactory.
   * @see HttpsURLConnection#setSSLSocketFactory(SSLSocketFactory)
   * @see HttpsURLConnection#setDefaultSSLSocketFactory(SSLSocketFactory)
   */
  val sc = SSLContext.getInstance("SSL")
  sc.init(null,
          Array(new TrustEverythingSSLTrustManager(): TrustManager),
          null)
  val socketFactory = sc.getSocketFactory()

  /** Automatically trusts all SSL certificates in the current process this is dangerous.  You should
   * probably prefer to configure individual HttpsURLConnections with trustAllSSLCertificates
   * @see #trustAllSSLCertificates(HttpsURLConnection)
   */
  def trustAllSSLCertificatesUniversally() = {
    HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory)
  }

  /** Configures a single HttpsURLConnection to trust all SSL certificates.
   *
   * @param connection an HttpsURLConnection which will be configured to trust all certs
   */
  def trustAllSSLCertificates(connection: HttpsURLConnection) = {
    connection.setSSLSocketFactory(socketFactory)
    connection.setHostnameVerifier(new HostnameVerifier() {
      def verify(s: String, sslSession: SSLSession) = true
    })
  }
}

/** Provides a mechanism to trust all SSL certificates */
class TrustEverythingSSLTrustManager extends X509TrustManager {

  def getAcceptedIssuers: Array[java.security.cert.X509Certificate] = null

  def checkClientTrusted(certs: Array[java.security.cert.X509Certificate],
                         authType: String) = {}

  def checkServerTrusted(certs: Array[java.security.cert.X509Certificate],
                         authType: String) = {}

}
