#!/usr/bin/env python3
"""Read-only SOCKS remote-DNS HTTPS probe. Run INSIDE approved consumer namespace.

No Telegram bot token needed. Default dry-run does not open sockets.
"""
import argparse
import ipaddress
import socket
import ssl
import struct


def exact(sock, length):
    result = b""
    while len(result) < length:
        chunk = sock.recv(length - len(result))
        if not chunk:
            raise ValueError("unexpected EOF")
        result += chunk
    return result


def socks_https(proxy_host="xray-proxy"):
    # Fixed destination: this tool cannot be used as arbitrary host/port scanner.
    domain = b"api.telegram.org"
    with socket.create_connection((proxy_host, 10808), timeout=10) as sock:
        sock.sendall(b"\x05\x01\x00")
        if exact(sock, 2) != b"\x05\x00":
            raise ValueError("SOCKS auth failed")
        sock.sendall(b"\x05\x01\x00\x03" + bytes([len(domain)]) + domain + struct.pack("!H", 443))
        head = exact(sock, 4)
        if head[:3] != b"\x05\x00\x00":
            raise ValueError("SOCKS connect failed")
        if head[3] == 1:
            exact(sock, 4)
        elif head[3] == 4:
            exact(sock, 16)
        elif head[3] == 3:
            exact(sock, exact(sock, 1)[0])
        else:
            raise ValueError("SOCKS atyp invalid")
        exact(sock, 2)
        with ssl.create_default_context().wrap_socket(sock, server_hostname=domain.decode()) as tls:
            tls.sendall(b"HEAD / HTTP/1.1\r\nHost: api.telegram.org\r\nConnection: close\r\n\r\n")
            status = tls.recv(256).split(b"\r\n", 1)[0]
            if not status.startswith(b"HTTP/1.") or status.split()[1] not in (b"200", b"301", b"302", b"404"):
                raise ValueError("unexpected HTTPS status")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--proxy-ip", help="approved private Xray IP from container metadata for host nsenter probe")
    args = parser.parse_args()
    if not args.execute:
        print("DRY-RUN: SOCKS5 remote-DNS TLS HEAD api.telegram.org; no network")
        return 0
    try:
        if args.proxy_ip:
            if not ipaddress.ip_address(args.proxy_ip).is_private:
                raise ValueError("private proxy metadata address required")
            socks_https(args.proxy_ip)
        else:
            socks_https()
    except (OSError, ValueError, IndexError):
        print("NOT_READY: proxy/DNS/TLS/HTTPS probe failed; details suppressed")
        return 1
    print("READY: SOCKS remote-DNS Telegram HTTPS reachable (not bot auth/polling acceptance)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
