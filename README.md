Apache HTTP async client SOCKSv4 Connection Demo
================================================

Plugs into Apache's HTTP async client to open a connection via a SOCKSv4 proxy.
This proxy can be set per request with the `RequestConfig`.

It currently only supports SOCKSv4.
Adding SOCKSv4a and SOCKSv5 should be straightforward with the given extension points.
Either a new session scheme `socks5` could be added or the SOCKS connection handling be adapted like in
`java.net.SocksSocketImpl` that can degrade von v5 to v4.

How it works
------------

 * A `SchemeIOSessionStrategy` is injected that supports the `socks` scheme.
     - It wraps the `IOSession` in a `Socks4IOSession`
     - The `Socks4IOSession` puts itself on the `IOSession` with a session attribute
     - A SOCKS `connect` package is sent (see [RFC](http://www.openssh.com/txt/socks4.protocol))
 * In order to be able to await the SOCKS reply the `DefaultConnectingIOReactor` was extended with
   `SocksConnectingIOReactor` to wrap the `IOEventDispatch` on `execute(..)` with the `SocksIOEventDispatchProxy`
 * The `SocksIOEventDispatchProxy`
     - extracts the `Socks4IOSession` from the session attributes on `inputReady(..)`
     - `Socks4IOSession` receives and verifies the SOCKS response
     - does some ugly error handling
