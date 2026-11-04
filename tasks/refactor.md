# refactor

Splitting the two giant Java files so many chats can work at once.

## Queue

- [ ] HorizonClient.java and HorizonServer.java are thousands of lines and nearly every chat edits them (most merge conflicts). Split each into files by area (screens, HUD, join/connections, models/atlases, keys, entities glue, worldgen glue, actions, mirror setup, tick), keeping behaviour identical. Do it in one go right after a boss merge; run the full regress set; push. In your report, list which new file each area now owns; the boss updates the areas' files.
- [ ] Same for game/server_mods.rs (or java_server.rs after the upstream sync): split by area.

## Needs

## Reports

