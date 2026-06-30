# Known Issues & Solutions

A log of build/runtime problems encountered during development and how they were resolved.

---

## Issue 1 — Local build fails: `Unable to establish loopback connection`

**Symptom**

Every `gradlew` invocation fails immediately with:

```
java.io.IOException: Unable to establish loopback connection
  at sun.nio.ch.PipeImpl$Initializer$LoopbackConnector.run()
  at sun.nio.ch.WEPollSelectorProvider.openSelector()
  at java.nio.channels.Selector.open()
```

**Root cause**

JDK 25 on Windows creates an internal AF_UNIX (Unix Domain) socket whenever
`Selector.open()` is called. The socket file path comes from the OS-level temp
directory (`GetTempPathW()`). On machines where the Windows username is longer
than 8 characters, `GetTempPathW()` returns the 8.3 short name — for example:

```
C:\Users\SIMPHI~1\AppData\Local\Temp
```

The tilde (`~`) in the short name causes Windows AF_UNIX `connect()` to return
`WSAEINVAL`, crashing the selector before any build work starts.

Gradle 9 always forks a child JVM to run the build (even with `--no-daemon` or
`org.gradle.daemon=false`). That child JVM inherits the same broken temp path.

**What does NOT fix it**

| Attempt | Why it fails |
|---|---|
| `org.gradle.jvmargs=-Djava.io.tmpdir=C:/T` | Gradle 9 strips all `-D` flags from `org.gradle.jvmargs` before passing them to the daemon JVM |
| `org.gradle.daemon=false` | Gradle 9 still forks a single-use daemon regardless |
| `gradlew --no-daemon` | Same — single-use daemon is always forked |
| `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=C:/T` alone | `PipeImpl` calls native `GetTempPathW()` directly, not `System.getProperty("java.io.tmpdir")` |
| Disabling Npcap Loopback Adapter | Unrelated — not the cause |

**Fix**

Override the OS-level temp variables in the same shell session before running
`gradlew`. `C:\T` must already exist (it does on this machine):

```powershell
$env:TEMP = "C:\T"; $env:TMP = "C:\T"; $env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=C:\T"
.\gradlew.bat :base:buildRelease
```

Setting `TEMP` and `TMP` overrides what `GetTempPathW()` returns for the child
JVM process, giving it a clean path with no tilde. The build completes in ~23s.

**Permanent fix (optional)**

Create `build.ps1` in the repo root:

```powershell
$env:TEMP = "C:\T"
$env:TMP  = "C:\T"
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=C:\T"
.\gradlew.bat :base:buildRelease
```

Then just run `.\build.ps1` instead of `gradlew` directly.

---

## Issue 2 — Discord feed messages garbled (emoji encoding)

**Symptom**

Build succeeds but Discord messages show garbage characters instead of emoji,
e.g. `â€œ` instead of `🏆`.

**Root cause**

`base/build.gradle` compiles Java sources with `options.encoding = 'windows-1252'`.
New source files created by tooling are saved in UTF-8. Emoji (`🏆`, `⚔️`, etc.)
are 3-4 byte UTF-8 sequences; when javac reads the file as windows-1252 those
bytes map to wrong characters or unmappable positions. Javac prints
`error: unmappable character (0x8F)` but still emits class files with corrupted
string literals.

**Fix applied**

All emoji removed from Discord feed strings. Replaced with plain readable text:

| Before | After |
|---|---|
| `🏆 **LEAD CHANGE** —` | `**LEAD CHANGE** -` |
| `⚔️ **BATTLE** —` | `**BATTLE** -` |
| `🔧 **NAME** pits` | `**NAME** pits` |
| `⏱️ **Halfway** —` | `**Halfway** -` |
| `🟡 IN PIT` | `IN PIT` |
| `⚠️` | `(!)` |

**Permanent fix (if emoji are wanted in future)**

Change `base/build.gradle` line:
```groovy
options.encoding = 'windows-1252'
```
to:
```groovy
options.encoding = 'UTF-8'
```
Then update `Main.java` line 96 to use `ü` instead of the literal `ü`
byte, since that file was originally saved in windows-1252.
