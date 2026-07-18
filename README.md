# Prefab Custom Addon

Prefab ships with a handful of buildings. After a while you get bored of them.

This addon adds an extension pack system: press **Z** in-game to browse and download community-made packs, or press **X** to make your own.

**Downloader** — browse community packs, pick one, drops into `prefab-extension/`, ready to use next time you load a world.

**Creator** — local workspace mode. Fill in a form, pick an NBT, pick a cover image, a few clicks and you have a standard extension pack (with ZIP). Everything lives in `prefab-work/` at your game root. Export NBT files using the vanilla Structure Block.

Requires: Prefab 1.0.7+, LDLib2 2.2+. Tested on NeoForge 21.1.233 / MC 1.21.1 / Java 21+.

## Keybindings

| Key | Action |
|---|---|
| Z | Open extension pack downloader |
| X | Open extension pack creator |

## Folder Layout

The workspace folder mirrors the published pack format:

```
prefab-work/
├── <pack_id>/
│   ├── information/
│   │   ├── <pack_id>.txt
│   │   └── cover.png
│   └── construction/
│       ├── <building_id>.nbt
│       ├── <building_id>.png
│       └── <building_id>.txt
└── <pack_id>.zip          ← auto-generated
```

Any change under `prefab-work/<id>/` triggers an automatic repack into `prefab-work/<id>.zip`. To install a pack manually, unzip the file into `prefab-extension/<id>/`.

## Building from Source

Java 21+ required.

1. Grab the Prefab jar from [CurseForge](https://www.curseforge.com/minecraft/mc-mods/prefab) or [Modrinth](https://modrinth.com/mod/prefab) and put it at `libs/prefab-neoforge-1.0.8.jar`
2. Grab the LDLib2 jar from [Modrinth](https://modrinth.com/mod/ldlib2) and put it at `libs/ldlib2-2.2.18.jar`
3. Run `gradlew build`. Output goes to `build/libs/`

If the build complains about missing Prefab classes, double-check the two jar paths above.

## Dependencies

| Mod | Version | Required | Purpose |
|---|---|---|---|
| Prefab | 1.0.7+ | yes | core — provides `GuiStructure`, `BuildBlock`, etc. |
| LDLib2 | 2.2+ | yes | UI utilities |

NeoForge 21.1.233 on Minecraft 1.21.1.

## License

MIT. See [LICENSE](LICENSE).

## Links

- [Prefab on CurseForge](https://www.curseforge.com/minecraft/mc-mods/prefab)
- [NeoForge](https://neoforged.net/)
- [LDLib2 on Modrinth](https://modrinth.com/mod/ldlib2)
