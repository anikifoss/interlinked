package requests


object UtilBypass:
  def clientCertSSLContext(cert: Cert, verifySslCerts: Boolean) =
    Util.clientCertSSLContext(cert, verifySslCerts)

  def noVerifySSLContext = Util.noVerifySSLContext
