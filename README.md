<div align="center">

<img src=".github/images/logo.png" width="128" alt="Throne logo">

# Throne for Android

**Android proxy client, powered by [sing-box](https://github.com/SagerNet/sing-box)**

[![Release](https://img.shields.io/github/v/release/throneproj/ThroneForAndroid?style=flat-square&label=release&color=2F80ED)](https://github.com/throneproj/ThroneForAndroid/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/throneproj/ThroneForAndroid/total?style=flat-square&logo=github&label=downloads)](https://github.com/throneproj/ThroneForAndroid/releases)
[![Stars](https://img.shields.io/github/stars/throneproj/ThroneForAndroid?style=flat-square&logo=github&label=stars)](https://github.com/throneproj/ThroneForAndroid/stargazers)
[![License](https://img.shields.io/github/license/throneproj/ThroneForAndroid?style=flat-square&color=6E7781)](LICENSE)
[![Telegram](https://img.shields.io/badge/Telegram-@ThroneProj-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/ThroneProj)

</div>

Throne for Android manages proxy profiles and subscriptions, routes traffic with rule-based routing profiles, and works either as a VPN or as a proxy-only service. It shares [Throne](https://github.com/throneproj/Throne) desktop's core and data model, so profiles, share links, route profiles and `.thrbackup` backups move freely between the two. See the [documentation](https://throneproj.github.io) for guides.

<p align="center">
  <img width="300" alt="Throne for Android main screen" src=".github/images/screenshot.png" />
</p>

## Installation

APKs for arm64-v8a, armeabi-v7a and x86_64 are on [GitHub Releases](https://github.com/throneproj/ThroneForAndroid/releases), with a `SHA256SUMS` file to verify them. Throne runs on Android 7.0 (API 24) or newer, including Android TV.

### Upgrading from 1.x
Releases from v2.0.0 on are signed with a new key, so Android refuses to install them over a 1.x release (signature conflict). Uninstall the old release first, then import your subscriptions and profiles again: old local configurations are not migrated.

## Supported protocols

- SOCKS
- HTTP(S)
- Shadowsocks
- Trojan
- VMess
- VLESS
- TUIC
- Hysteria
- Hysteria2
- AnyTLS
- Mieru
- Snell
- NaïveProxy
- Juicity
- TrustTunnel
- ShadowTLS
- Wireguard
- AmneziaWG
- MASQUE
- SSH
- DNSTT / VayDNS
- MasterDNS
- Xray VLESS (XHTTP/XDrive)
- OpenVPN/OpenConnect
- Custom Outbound (Both Sing-box and Xray)
- Custom Config (Both Sing-box and Xray)
- Chaining outbounds

## Subscription Formats

Various formats are supported, including share links, various JSON representation of Sing-box configs, and v2rayN link format as well as limited support for Shadowsocks and Clash formats.

Deeplinks are also supported, read the [documentation](https://throneproj.github.io/advanced/deeplinks/) for more information.

## Credits

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [XTLS/Xray-core](https://github.com/xtls/xray-core)
- [SagerNet/sing-box-dashboard](https://github.com/SagerNet/sing-box-dashboard)
- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)
- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)

