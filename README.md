# ExtraLog

Privacy-aware activity logging for Paper and Folia Minecraft servers.

ExtraLog records important server activity in clean local log files and can send structured notifications to Discord. It is designed to help staff investigate suspicious activity without exposing sensitive command arguments or private messages.

## Features

- Paper and Folia support for Minecraft 1.21.x
- Java 21 runtime
- Player join and quit logs
- Player and console command logs
- World, location, gamemode, ping, client, version, platform and hostname details when available
- Password and authentication argument protection
- Private message content protection
- Asynchronous single-writer local logging
- TEXT and JSONL log formats
- Daily files, size rotation, gzip compression and retention cleanup
- Configurable IP logging and public Discord IP masking
- Optional VPN/proxy detection with SQLite caching
- PROXYCHECK, IPHUB and IPQUALITYSCORE provider support
- Localized English and Turkish messages
- Discord webhooks for general, staff and security channels
- Optional Discord bot with `/status`, `/vpncheck` and `/reload`
- Folia-safe scheduling and shutdown flushing

## Installation

1. Install Java 21.
2. Install Paper or Folia 1.21.x.
3. Put `ExtraLog-1.0.0.jar` into the server's `plugins` folder.
4. Start the server once.
5. Edit `plugins/ExtraLog/config.yml`.
6. Restart the server or run `/extralog reload`.

The plugin creates English and Turkish language files in `plugins/ExtraLog/lang/` on first start.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/extralog reload` | Reload configuration and language files | `extralog.reload` |
| `/extralog vpncheck <player\|ip>` | Check a player or IP for VPN/proxy signals | `extralog.admin` |
| `/extralog vpnwhitelist add <ip\|player>` | Add an IP or player to the VPN whitelist | `extralog.admin` |
| `/extralog vpnwhitelist remove <ip\|player>` | Remove an IP or player from the VPN whitelist | `extralog.admin` |
| `/extralog testwebhook [general\|staff\|security]` | Send a webhook test message | `extralog.admin` |

Aliases: `/elog` is available for `/extralog`.

## Discord

### Webhooks

Discord webhooks are optional and disabled until valid URLs are configured. Events can be routed independently to:

- `general` for public activity
- `staff` for commands and staff-facing activity
- `security` for VPN/proxy alerts

General webhook IPs follow `ip-logging.public-discord-mask`. Staff and security webhooks show the full IP when IP logging is enabled.

### Bot

The Discord bot is optional and disabled by default. Configure it in `config.yml`:

```yaml
discord:
  bot:
    enabled: true
    token: "YOUR_BOT_TOKEN"
    guild-id: "YOUR_GUILD_ID"
    allowed-user-ids: ["YOUR_DISCORD_USER_ID"]
    allowed-role-ids: []
```

Use a guild ID while testing so slash commands appear quickly. Never publish the bot token, webhook URLs or VPN API keys.

## VPN and proxy detection

VPN checking is disabled by default. When enabled, the player's IP address is sent to the selected third-party provider. Results are reported as possible or suspected signals, never as confirmed identity or abuse.

Private/local addresses and whitelisted players or IPs are skipped. Results are cached in SQLite, and all provider requests are asynchronous.

Review the provider's privacy policy and your server's data-processing obligations before enabling this feature.

## Privacy

ExtraLog includes privacy controls for server owners:

- Disable IP logging globally with `ip-logging.enabled: false`.
- Mask IPs in the public Discord webhook with `public-discord-mask: true`.
- Keep authentication command arguments out of logs.
- Keep private message content out of logs.
- Keep Discord tokens, webhook URLs and API keys out of public files and screenshots.

## Log files

Logs are stored in `plugins/ExtraLog/logs/` by default.

TEXT logs are intended for staff reading. JSONL logs are intended for tools and automation. Completed daily files can be compressed to `.log.gz`, and old files can be removed according to the configured retention period. The current day's file is never compressed or removed during maintenance.

## Configuration

The bundled `config.yml` contains short explanations for each setting. The main sections are:

- `language`
- `ip-logging`
- `log-details`
- `file-logging`
- `events`
- `commands`
- `vpn-check`
- `discord`

Configuration and language files can be reloaded with `/extralog reload`.

## Permissions

- `extralog.admin` — access to administrative ExtraLog commands
- `extralog.reload` — reload configuration and language files
- `extralog.viewip` — view unmasked IP addresses in Minecraft command output

Administrative permissions should only be granted to trusted staff.

## Compatibility

| Component | Supported |
| --- | --- |
| Paper | 1.21.x |
| Folia | 1.21.x |
| Java | 21 or newer |
| Floodgate | Optional; used when available for platform information |

## Building

Windows:

```text
gradlew.bat shadowJar
```

Linux/macOS:

```text
./gradlew shadowJar
```

The built plugin is created at `build/libs/ExtraLog-1.0.0.jar`.

## Scope

ExtraLog currently focuses on activity logging, privacy-aware Discord notifications, optional VPN/proxy checks and the optional Discord bot. Player database storage and automated alt-account detection are not included in this release.

## License

ExtraLog is released under the [MIT License](LICENSE).
