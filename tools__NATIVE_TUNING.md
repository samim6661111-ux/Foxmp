# libfoxmp native tuning (exe-patch-free)
`libfoxmp.so` calls GTA V's internal task functions directly. Offsets + prologue bytes were taken from your
`libgtav.so` (sha256 starts `b7f73614fa4299ff`). If the build differs the prologue check prints MISMATCH and the function stays off.

All functions are OFF by default. Enable by creating `/storage/emulated/0/Games/GTAV/foxmp/native.cfg`, one name per line:
```
TASK_ENTER_VEHICLE
TASK_WARP_PED_INTO_VEHICLE
TASK_GO_STRAIGHT_TO_COORD
TASK_PLAY_ANIM
```
`adb logcat -s FoxMP` shows `... ENABLED`, `CRASHED (wrong args)`. A wrong argument layout is caught by a signal guard and the function is switched off again (the game keeps running) - send me that log line and I fix the layout in `FoxNative.java` (`ints` go to x0..x7, `floats` to s0..s7).
Known risk: these functions run on the Java thread, not the game thread. Keep them OFF until logcat shows a clean run.
