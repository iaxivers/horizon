# The task board

One file per area. **No merging needed**: this branch holds only these files, and each chat
edits only its own area's file.

## The loop (every worker, every task)

1. `git fetch origin tasks && git show origin/tasks:tasks/<area>.md` — your next task is the
   first unchecked `- [ ]`.
2. `git fetch origin && git merge origin/java-bridge` into your work branch, do the task, run
   `python3 tools/java_bridge/regress.py` (your area's cases; `--java-only` when only Java
   changed), push your work branch `bridge-<area>-<n>`.
3. Update your file on this branch: tick the task (`- [x]`, with your branch name), add a short
   report under "Reports" (works / doesn't / how to test). Push:
   ```sh
   git worktree add /tmp/board origin/tasks && cd /tmp/board   # once
   git pull --rebase origin tasks   # edit tasks/<area>.md   git commit -am "<area>: …"
   git push origin HEAD:tasks
   ```
   (A push refused? Put the report in your final message instead; the boss adds it.)
4. Take the next unchecked task. Stop after 6 tasks or when the list is empty, and say so.

The boss merges work branches into `java-bridge` as they come, and adds tasks to these files.
Never edit another area's file. If your work needs another area, write it under "Needs" in
your file.

## Rules

Read CLAUDE.md, docs/java-bridge.md and docs/java-bridge-boss.md. Commit as iaxivers with the
Co-Authored-By line; never commit `.local/` or Mojang files; fresh scratch dirs for every game
run; never run mod jars the sandbox blocks; be clear about what's tested headless.

## Areas

| Area | File | Who (put your name to claim it) |
|---|---|---|
| refactor | [tasks/refactor.md](tasks/refactor.md) | |
| sync | [tasks/sync.md](tasks/sync.md) | |
| upstream-prs | [tasks/upstream-prs.md](tasks/upstream-prs.md) | |
| entities | [tasks/entities.md](tasks/entities.md) | |
| worldgen | [tasks/worldgen.md](tasks/worldgen.md) | |
| dims | [tasks/dims.md](tasks/dims.md) | |
| pack | [tasks/pack.md](tasks/pack.md) | |
| compat | [tasks/compat.md](tasks/compat.md) | |
| client | [tasks/client.md](tasks/client.md) | |
| multiplayer | [tasks/multiplayer.md](tasks/multiplayer.md) | |
| mixins | [tasks/mixins.md](tasks/mixins.md) | |
| gpu | [tasks/gpu.md](tasks/gpu.md) | |
| beta | [tasks/beta.md](tasks/beta.md) | |
