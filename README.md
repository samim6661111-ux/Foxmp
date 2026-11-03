# FoxMP v4 - GTA V Android (COM.FOXENT.GTAV) multiplayer

## Ki ki ache
| Part | Kaj |
|---|---|
| `server/foxmp_server.py` | login, friend request/accept/reject, world join, UDP realtime relay, voice relay, mission-instancing, host save upload/download |
| `client/` (Java, `com.gtavsource.android`) | **"Multiplayer" page** in the game's own menu (launcher HTML + pause menu), in-game FOX button/radar, sync, voice, save-sync. Uses the game's own `TrainerBridge.NATIVE` commands |
| `native/` -> `libfoxmp.so` | safe direct calls to GTA internal tasks (enter vehicle, goto, anim ...). Off by default, see `tools/NATIVE_TUNING.md` |
| `tools/patch_apk.py` | APK te hook + dex + libfoxmp.so boshiye signed APK banay |
| `.github/workflows/build.yml` | GitHub e push korle build hoy (libfoxmp.so + dex, optional patched APK) |

## Server chalano (PC / VPS / Termux)
`python3 server/foxmp_server.py` - ports TCP 7778, UDP 7777, HTTP 7779 (3 tai forward/open korte hobe).

## APK build
1. GitHub e ei folder push koro -> Actions -> "FoxMP build" -> Run workflow. `apk_url` e tomar GTA APK er direct link dile patched APK ready hoy (artifact: `foxmp-build`).
2. Ba local: `python3 tools/patch_apk.py GTAV.apk out.apk --tools <apktool.jar folder> --libfoxmp libfoxmp.so --android-jar android.jar` (needs Java 17 + Android build-tools).
Note: notun signature, tai agerta uninstall kore install korte hobe (game data `/sdcard/Games/GTAV` thake).

## HTML launcher kibhabe jora
Launcher HTML ta alada file na: `GtavPages.buildLauncherHtml` game er native menu view gulo (`GtavPages.ser`) WebView e mirror kore. Tai patch ta 11th page "Multiplayer" (titles + ORDER + render) add kore, `FoxMenu` native view banay, ar HTML launcher e nijei ashe. Pause menu te o same page.

## Kibhabe chalabe
Launcher/pause menu -> **Multiplayer** page -> server IP + naam -> Connect -> friend request / Accept / Reject -> friend online thakle Join / Invite. In-game FOX button (top-right) o ache.
Host: "Host save upload" (apnar `SGTA5*` slot gulo upload hoy). Guest Join korle download progress -> "Setup complete" -> game e oi save load koro.
Leave world dile guest er nijer save backup theke ferot ashe. Mission ON dile onno der kache dekha jay na (instanced); progress nijer save e.

## Ki kaj kore, ki na (shotti kotha)
- GTA Online er moto multiplayer: session/world join, friend + invite, kick, player list, radar, voice, text chat, shared time (host er ghori). PvP damage sync ar vehicle seat sync ekhono nei (native verify lagbe).
- Tested: server (tests/test_server.py), Java compile, voice codec, hook patch on your real dex, native prologue table vs your libgtav.so.
- NOT tested on phone: UI, ghost movement smoothness, voice latency, save install, native calls. Eta prothom run e logcat dekhe thik korte hobe.
- Remote player ekhon ped hishebe `entity.move` diye move kore (real driving/anim/aim natives ready kintu off - NATIVE_TUNING.md).
- Map blip / mathar upor nam: ei build e game blip function paoa jay ni, tai radar (top-left) dile friend er naam + doori dekhay.
- Mission auto-detect nei: "Mission" button manual.

## v4.1 - gari + activity sync
- UDP 'J' packet ekhon 36 byte: + vehicle model hash, seat, flags (aim/shoot/jump/sprint/dead/engine). Purono 30 byte packet o cholbe.
- Friend gari te uthle tomar game e oi model er gari spawn hoy (model hash -> name table `FoxSync.CARS`, na pele `sultan`), gari friend er sathe move/rotate kore.
- Vehicle spawn command naam auto-probe hoy (`vehicle.spawn`, `veh.spawn`, `car.spawn`, `vehicle.create`). Kono ta kaj na korle toast e Diag dekhay - sheta amake pathao.
- Ped ke gari te bosano: `TASK_WARP_PED_INTO_VEHICLE` native.cfg te on thakle real seat, na hole ped gari er upor/bhitore ride kore (fallback).
- Aim/shoot flag: `TASK_AIM_GUN_AT_COORD` on thakle friend er ped aim kore. Dead -> ragdoll toggle.
- Snapshot e jodi `vehicleModel`/`seat`/`aiming`/`shooting` field thake tahole kaj kore; na thakle default (sultan, seat -1) use hoy.
