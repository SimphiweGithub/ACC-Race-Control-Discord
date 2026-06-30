# Workaround for JDK 25 + tilde-in-TEMP AF_UNIX socket crash (see KNOWN_ISSUES.md #1)
# Run this instead of gradlew directly.
$env:TEMP = "C:\T"
$env:TMP  = "C:\T"
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=C:\T"

& .\gradlew.bat :base:buildRelease @args
