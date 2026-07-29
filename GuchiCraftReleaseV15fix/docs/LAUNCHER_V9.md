# Launcher v9 changes

- Saves nickname, selected memory, and hide-window preference in `%APPDATA%\\Guchicraft\\launcher.properties`.
- Applies selected memory to Minecraft with `-Xmx` and a safe `-Xms512M`.
- Uses Mojang's `--quickPlayMultiplayer host:port` argument for direct connection.
- Adds an **Open game folder** button.
- Writes launch metadata and Minecraft output to `%APPDATA%\\Guchicraft\\game\\logs\\launcher-game.log`.
- Can hide while Minecraft is running and restore itself after the game exits.
