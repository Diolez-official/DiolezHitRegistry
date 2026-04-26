# DiolezHitregistry

<div align="center">

![Minecraft](https://img.shields.io/badge/Minecraft-1.21-brightgreen?style=for-the-badge&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==)
![Fabric](https://img.shields.io/badge/Loader-Fabric-blue?style=for-the-badge)
![Environment](https://img.shields.io/badge/Environment-Client--Side-orange?style=for-the-badge)
![License](https://img.shields.io/badge/License-MIT-purple?style=for-the-badge)

**Client-side ghost hit elimination, combat prediction, and utility desync fixes for Minecraft 1.21.**
No server mod required.

[Installation](#installation) · [Configuration](#configuration) · [Anti-Cheat Info](#anti-cheat-compatibility) · [Architecture](#architecture)

</div>

---

## What Is This?

You swing on a player. The hit animation plays. Particles fly. Nothing happens. Their health doesn't move.

That's a **ghost hit** — caused by the gap between what your client sees and what the server processes at 20 TPS. It gets significantly worse the higher your ping is.

**DiolezHitregistry eliminates this feeling.** It applies immediate client-side visual feedback for every attack and utility action, then silently reconciles with the server's authoritative response. If the server agrees — nothing changes. If the server rejects the hit — the health bar smoothly fades back. No jarring snap. No confusion.

---

## Features

### ⚔️ Ghost Hit Elimination
Every swing instantly applies a visual health reduction to your target before the server confirms. Visual state is corrected smoothly once the server responds (~1 RTT later).

### 🧠 Combat Prediction & Server Reconciliation
Every attack is timestamped the moment you swing. The mod tracks a live round-trip time estimate from your current ping and uses it to calibrate the tolerance window dynamically — wider on high-ping servers, tighter on low-ping ones.

### 🔧 Utility Desync Fixes
All predicted immediately, all rolled back gracefully if the server disagrees:

| Action | What's Fixed |
|---|---|
| 🟣 Ender Pearls | Arc and trajectory appear instantly on throw |
| 🏹 Bow & Crossbow | Projectile predicted on release |
| 🍗 Food & Potions | Consume animation plays without waiting for server |
| 🧱 Block Placement | Block appears in world before server acknowledgement |
| 🎒 Hotbar Switching | Slot change is instant, rolled back if server denies |

### 📊 Live RTT Calibration
Continuously measures your actual ping via the player list and uses it to dynamically tune prediction windows. Works well at 20ms, still feels great at 300ms+.

### 🐛 Developer Debug Overlay
Enable a real-time HUD showing:
- Estimated RTT and tick-boundary crossing risk
- Visual HP vs real HP on your current target
- Pending / confirmed / rolled back prediction status
- Entity position error (gap between visual and server-side position)

---

## Installation

### Requirements
- Java 21
- Minecraft 1.21
- [Fabric Loader](https://fabricmc.net/) `>=0.15.0`
- [Fabric API](https://modrinth.com/mod/fabric-api) `0.100.8+1.21`

### Steps
1. Download the latest `.jar` from [Releases](../../releases)
2. Drop it into `.minecraft/mods/`
3. Launch — config is auto-created at `.minecraft/config/hitregistry.json`

### Building from Source
```bash
git clone https://github.com/diolez/hitregistry.git
cd hitregistry
./gradlew build
# Output: build/libs/DiolezHitregistry-1.0.0.jar
```

For a Fabric dev environment:
```bash
./gradlew runClient    # Launch dev client
./gradlew genSources   # Attach Yarn mapped sources for IDE navigation
```

---

## Configuration

Config location: `.minecraft/config/hitregistry.json`

Auto-generated with safe defaults on first launch.

```json
{
  "enabled": true,
  "combatPredictionEnabled": true,
  "utilityPredictionEnabled": true,
  "hitExpiryTicks": 20,
  "latencyCompensationTicks": 2,
  "predictionStrength": 0.85,
  "rollbackLerpSpeed": 0.3,
  "pearlPredictionEnabled": true,
  "projectilePredictionEnabled": true,
  "itemUsePredictionEnabled": true,
  "blockPlacementPredictionEnabled": true,
  "utilityRollbackDelayMs": 400,
  "debugLogging": false,
  "debugOverlay": false
}
```

### Option Reference

| Field | Range | Default | Description |
|---|---|---|---|
| `enabled` | bool | `true` | Master toggle — disabling makes the mod completely inert |
| `combatPredictionEnabled` | bool | `true` | Combat hit prediction |
| `utilityPredictionEnabled` | bool | `true` | Utility desync fixes |
| `hitExpiryTicks` | 5–40 | `20` | Ticks to wait for server ack before classifying as ghost hit |
| `latencyCompensationTicks` | 0–10 | `2` | Extra tolerance ticks for high-ping servers |
| `predictionStrength` | 0.0–1.0 | `0.85` | How aggressively visual damage is applied before confirmation |
| `rollbackLerpSpeed` | 0.05–1.0 | `0.3` | Speed of visual HP snap-back on rollback |
| `utilityRollbackDelayMs` | 100–1000 | `400` | ms before rolling back an unconfirmed utility action |
| `debugLogging` | bool | `false` | Verbose prediction event logging |
| `debugOverlay` | bool | `false` | Real-time HUD panel |

### Presets

**High Ping (150ms+)**
```json
{
  "hitExpiryTicks": 30,
  "latencyCompensationTicks": 5,
  "predictionStrength": 0.90,
  "utilityRollbackDelayMs": 600
}
```

**Anti-Cheat Sensitive Servers**
```json
{
  "hitExpiryTicks": 12,
  "latencyCompensationTicks": 1,
  "predictionStrength": 0.70,
  "utilityRollbackDelayMs": 300
}
```

---

## Anti-Cheat Compatibility

This mod is designed to be invisible to server-side anti-cheat systems.

### What this mod NEVER does
| Action | Why it matters |
|---|---|
| Send extra attack packets | Would trigger hit-rate limiters (Grim, Vulcan) |
| Modify outgoing packet timing | Server-side timing manipulation = ban |
| Alter entity positions serverward | Position spoofing = instant Grim ban |
| Cancel incoming S2C packets | Would desync the client from the server |
| Extend attack reach | Out of scope — cheat territory |

### What this mod DOES do (safely)
| Action | Mechanism |
|---|---|
| Visual health reduction before server confirmation | Stored in a `HashMap<EntityId, Float>` — never touches `Entity.health` |
| Lerp visual health back on rollback | Frame-by-frame lerp in `tick()`, purely cosmetic |
| Track pending action records | Client-side `ArrayDeque`, zero network interaction |
| Inject into vanilla methods | `@Inject` at `RETURN` — read-only, `CallbackInfo` never cancelled |

**Tested against:** Grim Anticheat · Vulcan · NoCheatPlus · Matrix

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        PREDICTION FLOW                               │
│                                                                      │
│  PlayerAttackMixin          CombatPredictionManager                  │
│  (HEAD inject)      ──────► • PendingHit created                    │
│  attackEntity()             • Damage estimated                       │
│                             • Visual HP override applied             │
│                                       │                              │
│                    ┌──────────────────┘                              │
│                    ▼  (~1 RTT later)                                 │
│  ClientPlayNetworkHandlerMixin  →  PacketInterceptor                 │
│                                                                      │
│  EntityStatusS2CPacket (HURT)   → onServerHurtConfirmed() ✓         │
│    → Visual HP synced to server HP                                   │
│                                                                      │
│  EntityTrackerUpdateS2CPacket   → onServerHealthUpdate()             │
│    → Ghost detected → rollback → Visual HP lerps to real HP ✗       │
│                                                                      │
│  Expiry: no ack within hitExpiryTicks → treated as ghost             │
└─────────────────────────────────────────────────────────────────────┘
```

### Mixin Injection Map

| Target Class | Method | Point | Purpose |
|---|---|---|---|
| `ClientPlayerInteractionManager` | `attackEntity` | `HEAD` | Record attack, begin prediction |
| `ClientPlayerInteractionManager` | `interactItem` | `HEAD` | Pearl / bow / food prediction |
| `ClientPlayerInteractionManager` | `interactBlock` | `HEAD` | Block placement prediction |
| `ClientPlayNetworkHandler` | `onEntityStatus` | `RETURN` | Hurt confirmation |
| `ClientPlayNetworkHandler` | `onEntityTrackerUpdate` | `RETURN` | Health sync / ghost detection |
| `ClientPlayNetworkHandler` | `onAddEntity` | `RETURN` | Projectile / pearl confirmation |
| `ClientPlayNetworkHandler` | `onBlockUpdate` | `RETURN` | Block placement reconciliation |
| `ClientPlayerEntity` | `tick` | `TAIL` | Hotbar slot change tracking |
| `MinecraftClient` | `tick` | `HEAD`/`TAIL` | Pre-tick hook / RTT debug logging |
| `GameRenderer` | `render` | `TAIL` | Debug overlay trigger |

### Project Structure

```
src/main/java/diolez/hitregistry/
├── HitregistryClient.java               # Mod entrypoint
├── config/
│   └── HitregistryConfig.java           # Gson JSON config
├── prediction/
│   ├── CombatPredictionManager.java     # Hit prediction + rollback engine
│   ├── UtilityPredictionManager.java    # Utility desync fixes
│   ├── PendingHit.java                  # Combat prediction record
│   └── PendingUtilityAction.java        # Utility prediction record
├── network/
│   └── PacketInterceptor.java           # S2C packet routing hub
├── mixin/
│   ├── ClientPlayNetworkHandlerMixin.java
│   ├── PlayerAttackMixin.java
│   ├── ItemUsageMixin.java
│   ├── ClientPlayerEntityMixin.java
│   ├── MinecraftClientMixin.java
│   └── GameRendererMixin.java
└── util/
    ├── DamageEstimator.java             # Client-side damage approximation
    ├── TickClock.java                   # Monotonic tick counter
    ├── TickAlignedHitValidator.java     # Server tick boundary risk analysis
    ├── EntityInterpolationHelper.java   # Position projection utility
    └── DebugOverlayRenderer.java        # HUD debug panel
```

---

## Known Limitations

True zero-latency PvP is physically impossible over a network. Here is what we simulate and why each limit exists:

**Invincibility Frames (iFrames)**
After being hit, entities have a server-side 10-tick (~500ms) invincibility window. The client cannot track exact iFrame state for other players, so some hits during this window may appear as ghost hits locally even when they're valid server-side.

**Bow Draw Power**
Draw state is tracked server-side. Projectile velocity prediction may be slightly off if the bow wasn't at full draw.

**Damage Precision**
The `DamageEstimator` uses visible client attributes and applies a 10% conservative reduction. Actual server damage (post armour-toughness, Protection enchants) will differ slightly, producing a small visual correction when the server responds.

**Packet Batching**
Some server configurations batch health sync packets, delaying rollback triggers by an extra tick or two.

### What would require a server-side companion mod

| Feature | Why it needs server-side |
|---|---|
| Exact iFrame synchronisation | Server broadcasts when invincibility ends |
| Confirmed damage echo | Server sends actual post-mitigation damage value |
| Tick phase synchronisation | Server broadcasts current tick phase offset |
| Bow draw state sync | Server sends draw progress percentage |
| Hit rejection reason | Server includes denial reason in health update |

---

## Debug Output Reference

Enable `debugLogging: true` in config to see these in console:

```
[Hitregistry] Predicted hit on Steve — dmg=6.50, visualHP=18.0→12.5, pending=1
[Hitregistry] Hit confirmed by server — entity=1234, serverHP=11.5
[Hitregistry] GHOST HIT detected — rolling back entity=1234, HP restored to 18.0
[Hitregistry] Hit expired (no server ack within 22 ticks) — treating as ghost
[Hitregistry/Utility] Predicted ender pearl throw, tick=4521
[Hitregistry/Utility] Server confirmed ENDER_PEARL entity, id=5678
[Hitregistry] High latency detected: RTT ~9.2 ticks (~460ms) — consider increasing hitExpiryTicks
```

---

## License

MIT — see [LICENSE](LICENSE)

---

*Made by Diolez*
