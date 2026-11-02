package com.nasmanagerapp.data.network

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import javax.net.ssl.SSLException

/**
 * Whether [error] means the server couldn't be reached at all (timeout, connection refused,
 * unresolvable host…), as opposed to a TLS failure where the server did answer.
 *
 * Used to decide whether a missing Android 17 local network permission is a plausible cause:
 * a blocked LAN connection surfaces as a plain socket timeout, never as a TLS error.
 */
internal fun isServerUnreachable(error: Throwable): Boolean =
    error is IOException && error !is SSLException

/**
 * Whether [host] (as typed by the user, IP literal or hostname) is on the local network — the only
 * case where Android 17's local network permission applies. Public addresses, VPNs reaching a
 * public/CGNAT range (e.g. Tailscale's 100.64.0.0/10) and loopback don't need it.
 *
 * A hostname is resolved through DNS, which Android leaves unblocked even without the permission
 * (port 53 is exempt) — except `.local` (mDNS) names, which do need it and are recognized by
 * their suffix instead. Blocking: call off the main thread. An unresolvable hostname counts as
 * not local.
 */
internal fun isLocalNetworkHost(
    host: String,
    resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
): Boolean {
    val normalized = host.trimEnd('.').lowercase()
    if (normalized.endsWith(".local")) return true
    val addresses = try {
        resolve(normalized)
    } catch (e: IOException) {
        return false
    } catch (e: SecurityException) {
        return false
    }
    return addresses.any(::isLocalNetworkAddress)
}

/**
 * Private IPv4 (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10) and IPv6 unique
 * local (fc00::/7) addresses.
 */
internal fun isLocalNetworkAddress(address: InetAddress): Boolean =
    address.isSiteLocalAddress ||
        address.isLinkLocalAddress ||
        (address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC)
