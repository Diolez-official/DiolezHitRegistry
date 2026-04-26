# DiolezHitregistry — Fabric 1.21 PvP Desync Elimination Mod

> Client-side ghost hit elimination, combat prediction, and utility desync fixes
> for Minecraft 1.21 (Fabric). Zero server-side dependencies.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        PREDICTION FLOW                                   │
│                                                                          │
│  ┌──────────────┐   PlayerAttackMixin     ┌────────────────────────┐    │
│  │ Local Player │ ──── HEAD inject ──────► │ CombatPredictionMgr   │    │
│  │   Swings     │                         │  • PendingHit created  │    │
│  └──────────────┘                         │  • Damage estimated    │    │
│                                           │  • Visual HP overridden│    │
│                                           └────────────┬───────────┘    │
│                                                        │                │
│            ┌───────────────────────────────────────────┘                │
│            ▼                                                             │
│  ┌──────────────────────────────────────────────┐                       │
│  │         S2C PACKET ARRIVES (1 RTT later)     │                       │
│  │  ClientPlayNetworkHandlerMixin @ RETURN       │                       │
│  │                                              │                       │
│  │  EntityStatusS2CPacket (status=2, HURT)      │                       │
│  │    → PacketInterceptor.onEntityStatus()      │                       │
│  │      → CombatMgr.onServerHurtConfirmed()     │                       │
│  │        → PendingHit.acknowledge(true) ✓      │                       │
│  │        → Visual HP synced to server HP       │                       │
│  │                                              │                       │
│  │  EntityTrackerUpdateS2CPacket (HP unchanged) │                       │
│  │    → PacketInterceptor.onEntityHealthSync()  │                       │
│  │      → CombatMgr.onServerHealthUpdate()      │                       │
│  │        → Ghost detected → rollback() ✗       │                       │
│  │        → Visual HP lerps back to real HP     │                       │
│  └──────────────────────────────────────────────┘                       │
│                                                                          │
│  EXPIRY PATH: PendingHit not ack'd within hitExpiryTicks                │
│    → Treated as ghost → visual HP restored                              │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                       UTILITY DESYNC FLOW                                │
│                                                                          │
│  ItemUsageMixin          UtilityPredictionMgr      PacketInterceptor    │
│  ─────────────           ─────────────────────     ─────────────────    │
│  interactItem() ────────► onPearlThrow()                                │
│  (ender pearl)           onProjectileLaunch()   ◄── AddEntityS2CPacket  │
│  interactBlock() ────────► onBlockPlace()        ◄── BlockUpdateS2CPacket│
│  ClientPlayerEntityMixin► onItemSwitch()                                │
│                                                                          │
│  If server confirms within rollbackDelayMs → acknowledge(true) ✓        │
│  If timeout expires → rollback() (despawn entity / clear animation)     │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                         MIXIN INJECTION MAP                              │
│                                                                          │
│  Target Class                    Method          Point   Purpose         │
│  ──────────────────────────────────────────────────────────────────     │
│  ClientPlayerInteractionManager  attackEntity    HEAD    Record attack   │
│  ClientPlayerInteractionManager  interactItem    HEAD    Record use      │
│  ClientPlayerInteractionManager  interactBlock   HEAD    Record place    │
│  ClientPlayNetworkHandler        onEntityStatus  RETURN  Hurt confirm    │
│  ClientPlayNetworkHandler        onEntityTracker RETURN  HP sync/ghost   │
│  ClientPlayNetworkHandler        onAddEntity     RETURN  Projectile ack  │
│  ClientPlayNetworkHandler        onBlockUpdate   RETURN  Block ack       │
│  ClientPlayerEntity              tick            TAIL    Slot tracking   │
│  MinecraftClient                 tick            HEAD    Pre-tick hook   │
│  MinecraftClient                 tick            TAIL    RTT logging     │
│  GameRenderer                    render          TAIL    Debug overlay   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Project File Tree

```
DiolezHitregistry/
├── build.gradle
├── gradle.properties
├── settings.gradle
└── src/main/
    ├── java/diolez/hitregistry/
    │   ├── HitregistryClient.java               ← Mod entrypoint
    │   ├── config/
    │   │   └── HitregistryConfig.java           ← JSON config (Gson)
    │   ├── prediction/
    │   │   ├── CombatPredictionManager.java      ← Hit prediction + rollback
    │   │   ├── UtilityPredictionManager.java     ← Utility desync fixes
    │   │   ├── PendingHit.java                  ← Combat prediction record
    │   │   └── PendingUtilityAction.java         ← Utility prediction record
    │   ├── network/
    │   │   └── PacketInterceptor.java            ← S2C packet routing hub
    │   ├── mixin/
    │   │   ├── ClientPlayNetworkHandlerMixin.java ← S2C packet intercepts
    │   │   ├── PlayerAttackMixin.java             ← Attack capture
    │   │   ├── ItemUsageMixin.java               ← Item use / block place
    │   │   ├── ClientPlayerEntityMixin.java       ← Slot switch tracking
    │   │   ├── MinecraftClientMixin.java          ← Tick loop hooks
    │   │   └── GameRendererMixin.java             ← Debug overlay hook
    │   └── util/
    │       ├── DamageEstimator.java              ← Client-side damage calc
    │       ├── TickClock.java                    ← Monotonic tick counter
    │       ├── TickAlignedHitValidator.java       ← Tick boundary risk
    │       ├── EntityInterpolationHelper.java     ← Position projection
    │       └── DebugOverlayRenderer.java         ← HUD debug panel
    └── resources/
        ├── fabric.mod.json
        └── hitregistry.mixins.json
```

---

## Building & Running

### Prerequisites
- Java 21 (Temurin or Adoptium recommended)
- Git

### Steps

```bash
# 1. Clone / place the project
cd DiolezHitregistry

# 2. Build the mod jar
./gradlew build
# On Windows: gradlew.bat build

# Output: build/libs/DiolezHitregistry-1.0.0.jar

# 3. Run the Fabric dev client for testing
./gradlew runClient

# 4. To regenerate Yarn mappings sources (for IDE navigation):
./gradlew genSources
```

### Installing
Copy `build/libs/DiolezHitregistry-1.0.0.jar` to your `.minecraft/mods/` folder alongside:
- `fabric-loader-0.15.x.jar`
- `fabric-api-0.100.x+1.21.jar`

---

## Configuration

Config file location: `.minecraft/config/hitregistry.json`

Created automatically on first launch with safe defaults.

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

### Tuning for High Ping (150ms+)
```json
{
  "hitExpiryTicks": 30,
  "latencyCompensationTicks": 5,
  "predictionStrength": 0.90,
  "utilityRollbackDelayMs": 600
}
```

### Conservative (Anti-Cheat Sensitive Servers)
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

### What this mod does NOT do
| Action | Status | Reason |
|--------|--------|--------|
| Send extra attack packets | ✗ Never | Would trigger hit-rate limiters (Grim, Vulcan) |
| Modify outgoing packet timing | ✗ Never | Server-side timing manipulation = ban |
| Alter entity positions serverward | ✗ Never | Position spoofing = instant Grim ban |
| Cancel incoming S2C packets | ✗ Never | Would desync client from server |
| Increase attack reach | ✗ Never | Out of scope, cheat territory |

### What this mod DOES do
| Action | Safe? | Mechanism |
|--------|-------|-----------|
| Apply visual health reduction before server confirms | ✅ | Stored in `visualHealthOverrides` HashMap, never touches Entity.health field |
| Lerp visual health back on rollback | ✅ | Frame-by-frame lerp in tick(), purely cosmetic |
| Track pending action records | ✅ | Client-side ArrayDeque, no network interaction |
| Register HudRenderCallback for debug overlay | ✅ | Standard Fabric API |
| Inject into vanilla methods with @Inject | ✅ | Read-only post-execution, no cancel() calls |

### Tested Conceptually Against
- **Grim Anticheat**: No packet manipulation, no reach extension, no velocity injection → safe
- **Vulcan**: No timer abuse, no FastPlace, no attack rate increase → safe  
- **NoCheatPlus**: No packet spoofing, standard movement → safe
- **Matrix**: No entity flag manipulation, no inventory exploit → safe

---

## Known Limitations & Why True 0ms Is Impossible

### The Physics of Latency

Even with perfect prediction, a 150ms ping means:
1. Your attack packet takes ~75ms to reach the server
2. The server's response takes ~75ms to return
3. During those 150ms, the target may have moved, been healed, or blocked

No client-side mod can eliminate this physical delay. What we can do:

| Limitation | What We Simulate Instead |
|-----------|--------------------------|
| Server doesn't confirm hit for ~RTT ms | Visual health drop immediately; corrected on confirmation |
| Entity moves between our visual position and server position | Position error estimated via `EntityInterpolationHelper`; debug overlay shows ghost risk |
| Server tick boundary crossing (hit lands in wrong tick) | `TickAlignedHitValidator` extends tolerance window by 0–1 ticks |
| Armour/enchant interactions unknown client-side | `DamageEstimator` applies 10% conservative fudge factor |
| Invincibility frames (server-authoritative) | `hitExpiryTicks` window absorbs the iFrame timing window |

### Specific Hardcoded Minecraft Limits

1. **Invincibility Frames (iFrames)**: After being hit, a vanilla entity has a 10-tick (~500ms) invincibility window server-side. Client doesn't track this precisely for other players. We mitigate via the expiry window but cannot eliminate false ghost-hit reads during iFrames.

2. **Bow Draw State**: The bow's draw power is tracked server-side. A client-only mod cannot know the exact draw state at the moment of release, causing minor projectile speed estimation errors.

3. **Entity Attribute Sync Lag**: Health is synced via EntityTrackerUpdateS2CPacket when it changes. On some server configurations, this packet is rate-limited or batched, delaying our rollback trigger.

4. **Anti-Cheat Watchdog Windows**: Servers like Grim analyse 20-tick rolling windows of client behaviour. Aggressive prediction settings that send packets at unusual cadences could theoretically create statistical anomalies in those windows, even without explicit cheat behaviour.

---

## Testing & Debugging

### Enable Debug Mode
In `hitregistry.json`:
```json
{
  "debugLogging": true,
  "debugOverlay": true
}
```

### Debug Overlay Reads
The HUD overlay (top-left corner) shows:
- `[Hitregistry] Combat: ON | Utility: ON` — feature status
- `RTT: ~120ms (2.4 ticks) | Phase: 23ms/50ms | Risk: LOW | +0 ticks` — latency and tick alignment
- `Target: Steve | Visual HP: 16.2 / Real HP: 18.0 / Max: 20.0 [PENDING]` — prediction in-flight
- `Position error: 0.183 blocks` — gap between visual and server entity position

### Console Log Patterns
```
[Hitregistry] Predicted hit on Steve — dmg=6.50, visualHP=18.0→12.5, pending=1
[Hitregistry] Hit confirmed by server — entity=1234, serverHP=11.5
[Hitregistry] GHOST HIT detected — rolling back entity=1234, HP restored to 18.0
[Hitregistry] Hit expired (no server ack within 22 ticks) — treating as ghost
[Hitregistry] High latency detected: RTT ~9.2 ticks (~460ms) — consider increasing hitExpiryTicks
```

### Unit Test Checklist (Manual)
- [ ] Hit a stationary dummy on singleplayer → confirm no rollback (hit confirmed instantly)
- [ ] Hit a moving player at 0ms simulated ping → visual and real HP should be identical
- [ ] Simulate 200ms ping via router QoS → visual HP should drop immediately, then snap to real value ~200ms later
- [ ] Spam-click to trigger iFrames → verify mod doesn't spam predictions (cooldown guard in `PlayerAttackMixin`)
- [ ] Throw ender pearl → verify pearl arc appears immediately, verify rollback if server denies
- [ ] Place block on border between valid/invalid position → verify rollback if server cancels placement

---

## TODOs (Server-Side Coordination Required)

The following improvements require a paired server-side mod or Fabric server plugin:

1. **Exact iFrame Synchronisation**: Server sends a custom packet when entity invincibility ends → client can precisely open/close the prediction window without relying on the fixed `hitExpiryTicks` estimate.

2. **Confirmed Damage Echo**: Server sends back the actual damage dealt (post-armour, post-enchant) → client corrects visual HP more precisely than our estimated value.

3. **Tick Phase Synchronisation**: Server broadcasts its current tick phase offset → client can align `TickAlignedHitValidator` precisely instead of estimating from wall-clock time.

4. **Bow Draw State Sync**: Server sends draw progress percentage → client projectile prediction uses the correct velocity for the arc display.

5. **Hit Rejection Reason**: Server optionally includes a denial reason in the health update → client can distinguish "ghost hit" from "blocked" from "armour absorption" for better UI feedback.
