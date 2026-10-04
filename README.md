Jail Mod (Fabric)
=================
[![Get it on Modrinth](https://img.shields.io/badge/Get%20it%20on-Modrinth-black?style=for-the-badge&logo=modrinth&logoColor=00AF5C)](https://modrinth.com/project/ZJf231gD)
<a href="https://www.curseforge.com/minecraft/mc-mods/jail-logic-unofficial-update">
  <img
    alt="Get it on CurseForge"
    src="https://img.shields.io/badge/Get%20it%20on-CurseForge-f16436?style=for-the-badge&logo=curseforge&logoColor=white"
  />
</a>

The **Jail Mod** is a server-side mod that allows you to imprison players in a virtual prison, preventing them from interacting with the world until they are released. It also works in singleplayer worlds, where the host can use jail commands when commands are enabled.

----------------------------------------------------------------

Key Features
------------

*   **Temporary Imprisonment**: You can imprison a player for a specific amount of time, blocking interactions with blocks, entities, and objects, such as buckets of lava or water.
*   **Automatic or Manual Release**: The player is automatically released after the set time, or an admin can release them manually.
*   **Imprisonment Reason**: Add a reason to the command when needed. If omitted, the reason is `Unknown reason`.
*   **Command to know the remaining time**: Imprisoned players can check the time remaining until their release.
*   **Discord Webhook (optional)**: Sends jail and unjail events to a Discord webhook. BanHammer webhook reuse is server-side only and disabled by default.
*   **BanHammer-style Discord templates**: Supports `config/jailmod/discord-messages.json` in BanHammer-like format with auto patching for new fields.
*   **Singleplayer support**: Jail commands and restrictions work on the integrated server; the singleplayer host is recognized as an administrator when commands are enabled.
*   **Mod Menu settings**: Edit client defaults and per-world overrides for admin roles, jail and release positions, and Discord options when Mod Menu and Cloth Config are installed.


How to use:
-----------

1.  Build a prison (a closed structure).
2.  Set the coordinates where you want the prisoner to spawn with the command `/jail set x y z`  (example `/jail set 0 60 0`)
    
3.  Reload the configuration using `/jail reload` .
4.  Send someone to jail with a duration and optional reason, for example `/jail imprison playerexample 2m Griefing`.
5.  If you don't want to wait for the sentence to end, release the player early with `/unjail playerexample`.


Requirements
------------

*   Minecraft 26.3
*   Java 25
*   Fabric Loader
*   Fabric API
*   *(Optional, for the in-game config screen)* Mod Menu and Cloth Config


Installation
------------

1.  For a server, place the mod's `.jar` file in the server's `mods` folder. For singleplayer, place it in the Minecraft instance's `mods` folder.
2.  Start the server or world to generate configuration files. Install Mod Menu and Cloth Config on the client to edit the local settings from Mod Menu.


Available Commands
------------------

### 1. `/jail imprison player time [reason]`

*   **Description**: Jails a player for the specified duration. The reason is optional and defaults to `Unknown reason`.
*   **Who can use it**: Only admins or server operators.
*   **Syntax**:  `/jail imprison player_name duration [reason]`
*   **Duration units**: `s`, `m`, `h`, `d`, `w`/`wk`, `mo`/`mth`, `y`/`yr`, plus plural `wks`/`yrs` and the full names `second(s)`, `minute(s)`, `hour(s)`, `day(s)`, `week(s)`, `month(s)`, and `year(s)`. A unit can be attached directly to the number, such as `10seconds`; a bare number means seconds. Unknown suffixes also fall back to seconds. Months are 30 days and years are 365 days.
*   **Examples**: `/jail imprison Steve 300 Griefing`, `/jail imprison Steve 5m`, and `/jail imprison Steve 2years Breaking blocks`. The second example uses the default reason.

### 2. `/unjail player`

*   **Description**: Manually releases a player from jail before the time expires.
*   **Who can use it**: Only admins or server operators.
*   **Syntax**: `/unjail player_name`
*   **Example**: /unjail Steve  
    This command will manually release `Steve` from jail.

### 3. `/jail info`

*   **Description**: Allows incarcerated players to see the time remaining until release and the reason for their incarceration.
*   **Who can use it**: Only jailed players.
*   **Example**: `/jail info`This command shows the remaining time using only nonzero units, plus who jailed the player and the reason. For example: "You are jailed for 10 minutes by Alex. Reason: Griefing."

### 4. `/jail reload`

*   **Description**: Reloads configuration, language messages, and `discord-messages.json` without restarting the server.
*   **Who can use it**: Only admins or server operators.
*   **Example**: `/jail reload`This command reloads the mod's configuration, useful if the config files have been modified.

### 5. `/jail set`

* **Description**: Sets the coordinates where jailed players will spawn. This is where players will appear when they are sent to jail.
* **Who can use it**: Only admins or server operators.
* **Syntax**: `/jail set x y z`
* **Example**: `/jail set 0 60 0`  
  This command sets the jail spawn location to coordinates (0, 60, 0).

Interactions blocked during jail
--------------------------------

When a player is jailed, they cannot do the following:

*   Use **blocks**, such as doors, levers, or buttons.
*   Interact with **entities**, such as villagers or animals.
*   Use **lava or water buckets**.
*   Break or place blocks.

Automatic release
-----------------

*   Players will be automatically released from jail when the set time is up.
*   While jailed, players can check the remaining time using the `/jail info` command.

Configuration file
------------------

### `config/jailmod/config.json`

This file is automatically generated and updated. In a client installation, it stores the defaults used to initialize settings for new worlds and worlds opened for the first time. On a dedicated server, this file is that server's active configuration.

In Mod Menu, the **Client Defaults** category edits these defaults. The **This World** category edits the open singleplayer world's overrides and is disabled when no singleplayer world is open. Each scope groups settings under **General**, **Positions**, and **Discord**. Once a world has its own config, changes to client defaults do not change that world's settings.

Per-world settings are saved inside the world folder at `serverconfig/jailmod/config.json`. The first time a world opens, it starts with a copy of the current client defaults. Jail records are stored beside it in `serverconfig/jailmod/jail_data.json`. Dedicated servers use their own `config/jailmod/config.json` and `config/jailmod/jail_data.json`; change those files on the server when playing multiplayer.

The settings include:

*   **`admin_roles`**: Comma-separated list of roles or tags that grant /jail access. e.g. "op,admin,moderator"
*   **`use_previous_position`**: If set to `true`, players will be released in the position they were in before being jailed. If set to `false`, they will be released in a specific position.
*  **`return_to_last_location`**: If true, released players will be teleported back to the exact spot where they were jailed.
*   **`release_position`**: The fallback coordinates for releasing players if no other location (spawn/last) is used, active if `use_previous_position` is set to `false` or if `return_to_last_location` is set to `false`.
*   **`jail_position`**: The coordinates where players are held while in jail with coordinates `x`, `y`, `z`.
*   **`discord_webhook_url`**: Optional Discord webhook used for jail/un-jail embeds. Leave empty to use BanHammer's webhook when enabled in the world's settings.
*   **`use_banhammer_webhook`**: If `true` and `discord_webhook_url` is empty, JailMod may reuse the server-side BanHammer webhook. It is off by default and is not exposed in the Mod Menu screen.

#### Example configuration:
```
{
  "_config_guide": "JailMod Configuration Guide: \n- admin_roles: Comma-separated list of roles or tags that grant /jail access. Use \u0027op\u0027 to include server operators.\n- use_previous_position: If true, released players will be teleported to their original spawn point (if return_to_last_location is false or unavailable).\n- return_to_last_location: If true, released players will be teleported back to the exact spot where they were jailed.\n- jail_position: The coordinates where players are held while in jail.\n- release_position: The fallback coordinates for releasing players if no other location (spawn/last) is used.",
  "admin_roles": "op",
  "use_previous_position": true,
  "return_to_last_location": true,
  "discord_webhook_url": "",
  "use_banhammer_webhook": false,
  "release_position": {
    "x": 100,
    "y": 65,
    "z": 100
  },
  "jail_position": {
    "x": 20,
    "y": 64,
    "z": 142
  }
}
```

### `config/jailmod/discord-messages.json`

This file controls Discord webhook message templates in a BanHammer-style format and is auto-generated/auto-patched.

*   **`sendJailMessage`**: Enables jail webhook notifications.
*   **`jailMessage`**: Template for jail events.
*   **`sendUnjailMessage`** and **`unjailMessage`**: Template for manual unjail events.
*   **`sendAutoUnjailMessage`** and **`autoUnjailMessage`**: Template for auto unjail events.

`/jail reload` reloads this file too.
### `config/jailmod/language.txt`

This file contains the messages that are displayed in-game, customizable to match the tone or style of the server. If the file does not exist, it is automatically generated with default messages. Here are some of the messages you can modify:

*   **`jail_player`**: Message the player receives when they are jailed. Placeholders are `{time}`, `{reason}`, and `{actor}`.
    Example: `"You have been jailed by {actor} for {time}! Reason: {reason}"`
*   **`jail_broadcast`**: BanHammer-style jail announcement. `{player}` is red, `{actor}` gold, `{reason}` and `{time}` yellow, and other text white.
    Example: `"Player: {player} has been jailed by {actor}! Reason: {reason}. Expires in {time}"`
*   **`unjail_player_manual`**: Message the player receives when they are manually released from jail.  
    Example: `"You have been manually released from jail!"`
*   **`unjail_broadcast_manual`**: Message broadcast to all players on the server when a player is manually released from jail.  
    Example: `"{player} has been manually released from jail!"`
*   **`unjail_player_auto`**: Message the player receives when they are automatically released from jail after the time expires.  
    Example: `"You have been released after serving your sentence."`
*   **`unjail_broadcast_auto`**: Message broadcast to all players on the server when a player is automatically released from jail after the time expires.  
    Example: `"{player} has been released after serving their sentence."`
*   **`block_interaction_denied`**: Message informing the player that they cannot interact with blocks while in jail.  
    Example: `"You cannot interact with blocks while in jail!"`
*   **`entity_interaction_denied`**: Message informing the player that they cannot interact with entities while in jail.  
    Example: `"You cannot interact with entities while in jail!"`
*   **`bucket_use_denied`**: Message informing the player that they cannot use lava or water buckets while in jail.  
    Example: `"You cannot use lava or water buckets while in jail!"`
*   **`item_use_denied`**: Message informing the player that they cannot use items while in jail.  
    Example: `"You cannot use items while in jail!"`
*   **`block_break_denied`**: Message informing the player that they cannot break blocks while in jail.  
    Example: `"You cannot break blocks while in jail!"`
*   **`jail_time_added_player`** and **`jail_time_added_broadcast`**: Messages for added jail time. `{added}` and `{time}` are formatted durations, so do not add unit labels after them.
*   **`jail_info_message`**: Message shown by `/jail info`. `{time}` includes only nonzero years, months, days, hours, minutes, and seconds; `{actor}` is the admin who jailed or most recently extended the sentence; `{reason}` is the jail reason.
    Example: `"You are jailed for {time} by {actor}. Reason: {reason}"`
*   **`not_in_jail_message`**: Message shown if a player is not in jail and tries to use `/jail info`.  
    Example: `"You are not in jail!"`

Default language.txt example:
```
    jail_player=You have been jailed by {actor} for {time}! Reason: {reason}
    jail_broadcast=Player: {player} has been jailed by {actor}! Reason: {reason}. Expires in {time}
    unjail_player_manual=You have been manually released from jail!
    unjail_broadcast_manual={player} has been manually released from jail!
    unjail_player_auto=You have been released after serving your sentence.
    unjail_broadcast_auto={player} has been released after serving their sentence.
    block_interaction_denied=You cannot interact with blocks while in jail!
    entity_interaction_denied=You cannot interact with entities while in jail!
    bucket_use_denied=You cannot use lava or water buckets while in jail!
    item_use_denied=You cannot use items while in jail!
    block_break_denied=You cannot break blocks while in jail!
    jail_time_added_player=Your jail time has been extended by {actor}. Added {added}. Remaining: {time}. Reason: {reason}
    jail_time_added_broadcast={player}'s jail time has been extended by {actor}. Added {added}. Remaining: {time}. Reason: {reason}
    jail_info_message=You are jailed for {time} by {actor}. Reason: {reason}
    not_in_jail_message=You are not in jail!
```
### Usage Tips

Use the `/jail reload` command after changing configuration or language messages to apply the changes without having to restart the server. Add a clear reason when you want to tell the player why they were jailed. If you leave it out, the reason is `Unknown reason`.

Usage Examples
--------------

Set Jail spawn position:

`/jail set 0 60 0`

Jailing a player for an unfair action:

`/jail imprison Alex 600 Offending another player`

This jails Alex for 10 minutes with the reason "Offending another player".

Checking jail time:

`/jail info`

A jailed player can use this command to check how much time they have left.


